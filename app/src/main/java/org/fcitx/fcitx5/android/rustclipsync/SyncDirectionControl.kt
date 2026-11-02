/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal data class DirectionSnapshot(val sessionId: String?, val version: Long, val revoked: Boolean)
internal interface DirectionTransport : AutoCloseable {
    fun snapshot(session: StoredSession): DirectionSnapshot
    fun report(session: StoredSession, operation: DirectionOperation): DirectionControl
    override fun close() {}
}

/** WSS is control-only. Data continues over bounded HTTPS; fallback reuses the exact operation. */
internal class SyncDirectionTransport(private val http: SyncTransport, private val wsClient: OkHttpClient = DirectionSocket.client) : DirectionTransport {
    private var socket: DirectionSocket? = null
    override fun snapshot(session: StoredSession): DirectionSnapshot {
        val j = JSONObject(requireNotNull(http.request("GET", path(session), http.bearer(session))).toString(Charsets.UTF_8))
        require(j.getString("deviceId") == session.state.deviceId)
        val sid = if (j.isNull("sessionId")) null else j.getString("sessionId").also(SyncTransport::requireUuid)
        return DirectionSnapshot(sid, j.controlInteger("version"), j.getBoolean("revoked"))
    }
    override fun report(session: StoredSession, operation: DirectionOperation): DirectionControl {
        SyncTransport.requireUuid(operation.sessionId)
        require(operation.version in 1..MAX_CONTROL_VERSION)
        val body = operation.json()
        val reply = if (socket == null && !operation.connect) httpReport(session, operation) else try {
            if (socket == null || operation.connect) {
                socket?.close()
                socket = DirectionSocket(http.base.resolve("api/ws").toString(), http.bearer(session), wsClient)
            }
            requireNotNull(socket).report(body.put("type", if (operation.revoke) "SyncDirectionRevoke" else "SyncDirectionUpdate").toString())
        } catch (failure: HttpFailure) { throw failure }
        catch (_: IOException) {
            socket?.close(); socket = null
            httpReport(session, operation)
        }
        require(reply.getString("deviceId") == session.state.deviceId)
        require(reply.getString("sessionId") == operation.sessionId && reply.controlInteger("version") == operation.version)
        require(reply.getBoolean("revoked") == operation.revoke)
        val serverTime = reply.controlInteger("serverTime")
        if (operation.revoke) require(reply.isNull("expiresAt"))
        else require(reply.controlInteger("expiresAt") >= serverTime)
        return DirectionControl(operation.sessionId, operation.version, operation.direction, operation.paused)
    }
    private fun httpReport(session: StoredSession, operation: DirectionOperation): JSONObject {
        val data = operation.json().toString().toByteArray(Charsets.UTF_8)
        return JSONObject(requireNotNull(http.request(if (operation.revoke) "DELETE" else "PUT", path(session),
            http.bearer(session), data, "application/json")).toString(Charsets.UTF_8))
    }
    private fun path(s: StoredSession) = "api/devices/${s.state.deviceId}/sync-direction"
    override fun close() { socket?.close(); socket = null }
}

private fun JSONObject.controlInteger(name: String): Long {
    val value = get(name)
    require(value is Long || value is Int) { "invalid control integer" }
    return (value as Number).toLong().also { require(it in 0..MAX_CONTROL_VERSION) }
}

private fun DirectionOperation.json(): JSONObject = JSONObject().put("sessionId", sessionId).put("version", version).apply {
    if (!revoke) put("direction", direction.name).put("paused", paused).put("connect", connect)
}

/** One bounded outstanding control request; no credentials in a URL or subprotocol. */
private class DirectionSocket(url: String, auth: String, client: OkHttpClient) : WebSocketListener(), AutoCloseable {
    private val opened = CountDownLatch(1)
    private val messages = LinkedBlockingQueue<String>(2)
    @Volatile private var failure: IOException? = null
    private val socket = client.newWebSocket(Request.Builder().url(url.replaceFirst("https:", "wss:"))
        .header("Authorization", auth).header("X-RCS-Protocol", PROTOCOL_VERSION).build(), this)
    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (response.header("X-RCS-Protocol") != PROTOCOL_VERSION) failure = HttpFailure(426, "PROTOCOL_UNSUPPORTED")
        opened.countDown()
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        failure = if (response?.code == 426) HttpFailure(426, "PROTOCOL_UNSUPPORTED") else IOException("control channel unavailable")
        opened.countDown()
    }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failure = IOException("control channel closed") }
    override fun onMessage(webSocket: WebSocket, text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 4096 || !messages.offer(text)) {
            failure = IOException("invalid control response"); socket.cancel()
        }
    }
    fun report(text: String): JSONObject {
        try {
            if (!opened.await(5, TimeUnit.SECONDS)) throw IOException("control connection timeout")
            failure?.let { throw it }
            if (!socket.send(text)) throw IOException("control send failed")
            val reply = messages.poll(5, TimeUnit.SECONDS) ?: throw (failure ?: IOException("control ACK timeout"))
            val j = JSONObject(reply)
            if (j.getString("type") == "SyncDirectionError") {
                val code = j.getString("code")
                throw HttpFailure(when (code) { "UNAUTHENTICATED" -> 401; "DEVICE_MISMATCH", "DIRECTION_FORBIDDEN" -> 403;
                    "RATE_LIMITED" -> 429; "PROTOCOL_UNSUPPORTED" -> 426; "INVALID_DIRECTION" -> 400; else -> 409 }, code)
            }
            require(j.getString("type") == "SyncDirectionAck")
            return j
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("control request interrupted") }
    }
    override fun close() { socket.cancel() }
    companion object {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }
}

/** Serialize control mutations across runtime and settings; persist every uncertain operation. */
internal class SyncDirectionController(
    private val load: () -> StoredSession?,
    private val update: ((StoredSession) -> StoredSession) -> StoredSession,
    private val remote: DirectionTransport,
) : AutoCloseable {
    private var connectedSession: String? = null
    private var lastReport = 0L
    fun ensure(direction: SyncDirection, paused: Boolean, active: () -> Boolean): StoredSession = synchronized(lock) {
        ensureLocked(direction, paused, active, allowLogout = false)
    }
    /** Keep a settings operation and its accepted session atomic against runtime re-handshakes. */
    fun <T> withSession(direction: SyncDirection, paused: Boolean, operation: (StoredSession) -> T): T = synchronized(lock) {
        operation(ensureLocked(direction, paused, { true }, allowLogout = false))
    }
    private fun ensureLocked(direction: SyncDirection, paused: Boolean, active: () -> Boolean, allowLogout: Boolean): StoredSession {
        repeat(3) {
            check(active()) { "superseded run" }
            var s = requireNotNull(load())
            check(s.paired && (allowLogout || !s.logoutPending)) { "logout awaiting confirmation" }
            val c = s.control
            if (c != null && connectedSession == c.sessionId && c.direction == direction && c.paused == paused &&
                s.controlPending == null && System.nanoTime() - lastReport < TimeUnit.SECONDS.toNanos(20)) return s
            var op = s.controlPending?.takeIf { !it.revoke && it.direction == direction && it.paused == paused }
            if (op == null) {
                val sameConnection = c != null && connectedSession == c.sessionId
                val version = if (sameConnection) c.version else remote.snapshot(s).version
                check(version < MAX_CONTROL_VERSION) { "control version exhausted" }
                op = DirectionOperation(if (sameConnection) c.sessionId else UUID.randomUUID().toString(), version + 1,
                    direction, paused, !sameConnection)
                s = update { current -> check(current.state.deviceId == s.state.deviceId); current.copy(controlPending = op) }
            }
            try {
                val acknowledged = remote.report(s, requireNotNull(op))
                check(active()) { "superseded run" }
                val accepted = update { current ->
                    check(current.state.deviceId == s.state.deviceId && current.controlPending == op)
                    current.copy(direction = direction, enabled = !paused, control = acknowledged, controlPending = null,
                        state = if (direction.sends) current.state else current.state.localCleared())
                }
                connectedSession = acknowledged.sessionId; lastReport = System.nanoTime()
                return accepted
            } catch (failure: HttpFailure) {
                if (failure.code != "VERSION_STALE") throw failure
                connectedSession = null
                update { current -> if (current.controlPending == op) current.copy(controlPending = null) else current }
            }
        }
        throw IOException("direction state changed; retry later")
    }
    /** An expired HTTP lease can reconnect; a fenced/revoked session cannot revive itself. */
    fun recoverLease(failure: HttpFailure, active: () -> Boolean): Boolean = synchronized(lock) {
        if (failure.status != 409 || failure.code != "SESSION_OFFLINE" || !active()) return false
        connectedSession = null; remote.close()
        true
    }
    fun invalidate() = synchronized(lock) { connectedSession = null; remote.close() }
    /** A fresh paused CAS fences any older uncertain report before logout revokes it. */
    fun revoke(): Unit = synchronized(lock) {
        var s = update { it.copy(logoutPending = true, enabled = false, state = it.state.localCleared()) }
        var op = s.controlPending?.takeIf { it.revoke }
        if (op == null) {
            connectedSession = null
            s = ensureLocked(s.direction, true, { true }, allowLogout = true)
            val c = requireNotNull(s.control)
            check(c.version < MAX_CONTROL_VERSION)
            op = DirectionOperation(c.sessionId, c.version + 1, s.direction, true, false, true)
            s = update { it.copy(controlPending = op) }
        }
        remote.report(s, requireNotNull(op))
        update { current -> check(current.controlPending == op); current.copy(control = null, controlPending = null) }
        connectedSession = null; remote.close()
    }
    override fun close() = synchronized(lock) { remote.close(); connectedSession = null }
    companion object { private val lock = Any() }
}
