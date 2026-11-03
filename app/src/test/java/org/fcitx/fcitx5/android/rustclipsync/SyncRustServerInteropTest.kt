/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Opt-in, real Rust server; all data and credentials live in a disposable fixture. */
class SyncRustServerInteropTest {
    @Test fun androidTransportInteroperatesWithRustOverWssAndHttpFallback() {
        val binary = System.getenv("RCS_ANDROID_RUST_SERVER")
        assumeTrue("Set RCS_ANDROID_RUST_SERVER to opt in to real-server interoperability", binary != null)
        for (websocket in listOf(true, false)) fixture(requireNotNull(binary), websocket)
    }

    private fun fixture(binary: String, websocket: Boolean) {
        val temp = Files.createTempDirectory("rcs-android-real-server-")
        val backend = ServerSocket(0).use { it.localPort }
        val builder = ProcessBuilder(Path.of(binary).toRealPath().toString())
        builder.environment().keys.removeIf { it.startsWith("RCS_") }
        builder.environment().putAll(mapOf("RCS_LISTEN" to "127.0.0.1:$backend", "RCS_DATA_DIR" to temp.resolve("data").toString(),
            "RCS_DB_KEY" to "3d".repeat(32), "RCS_USER" to "fixture", "RCS_PASSWORD" to "disposable-android-rust-password"))
        val log = temp.resolve("server.log")
        val process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val upstream = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val wsControls = AtomicInteger()
        val httpControls = AtomicInteger()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                check(process.isAlive) { "fixture server exited" }
                val ready = runCatching { (URL("http://127.0.0.1:$backend/healthz").openConnection() as HttpURLConnection).run {
                    connectTimeout = 500; readTimeout = 500
                    try { responseCode == 200 } finally { disconnect() }
                } }.getOrDefault(false)
                if (ready) break
                check(System.nanoTime() < deadline) { "fixture server startup timed out" }
                Thread.sleep(50)
            }
            MockWebServer().use { proxy ->
                proxy.useHttps(tls.sslSocketFactory(), false)
                proxy.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/api/ws") {
                            if (!websocket) return MockResponse().setResponseCode(503)
                            return websocketProxy(request, upstream, backend, wsControls)
                        }
                        if (request.path!!.contains("/sync-direction") && request.method in listOf("PUT", "DELETE")) httpControls.incrementAndGet()
                        val connection = URL("http://127.0.0.1:$backend${request.path}").openConnection() as HttpURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 5_000; connection.readTimeout = 10_000
                            connection.requestMethod = request.method!!
                            for ((key, value) in request.headers) if (!key.equals("Host", true) && !key.equals("Connection", true) && !key.equals("Content-Length", true))
                                connection.setRequestProperty(key, value)
                            if (request.bodySize > 0) {
                                connection.doOutput = true
                                connection.outputStream.use { it.write(request.body.readByteArray()) }
                            }
                            val status = connection.responseCode
                            val body = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
                            return MockResponse().setResponseCode(status).setHeader("X-RCS-Protocol", connection.getHeaderField("X-RCS-Protocol") ?: "")
                                .setBody(okio.Buffer().write(body))
                        } finally { connection.disconnect() }
                    }
                }
                proxy.start()
                HttpsURLConnection.setDefaultSSLSocketFactory(trust.sslSocketFactory())
                val http = SyncTransport(proxy.url("/").toString())
                val basic = "Basic " + Base64.getEncoder().encodeToString("fixture:disposable-android-rust-password".toByteArray())
                fun registered(): StoredSession {
                    val id = UUID.randomUUID().toString()
                    val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
                    // The public transport sends this exact request; android.util.Base64 is an Android-only stub in JVM tests.
                    val body = JSONObject().put("deviceId", id).put("deviceToken", token).toString().toByteArray()
                    val reply = JSONObject(requireNotNull(http.request("POST", "api/devices", basic, body, "application/json")).toString(Charsets.UTF_8))
                    return StoredSession(proxy.url("/").toString(), token, true, true,
                        SyncState(reply.getString("epoch"), id, reply.getLong("ackRevision")))
                }
                var source = registered()
                var receiver = registered()
                val invalidBearer = receiver.copy(token = "00".repeat(32))
                val denied = assertThrows(HttpFailure::class.java) {
                    http.request("GET", "api/devices/${receiver.state.deviceId}/sync-direction", http.bearer(invalidBearer))
                }
                assertEquals(401, denied.status)
                assertEquals("UNAUTHENTICATED", denied.code)
                val sourceControl = SyncDirectionController({ source }, { f -> f(source).also { source = it } }, SyncDirectionTransport(http, client))
                val receiverControl = SyncDirectionController({ receiver }, { f -> f(receiver).also { receiver = it } }, SyncDirectionTransport(http, client))
                try {
                    sourceControl.ensure(SyncDirection.PUSH_ONLY, false) { true }
                    receiverControl.ensure(SyncDirection.RECEIVE_ONLY, false) { true }
                    val first = LocalCopy(UUID.randomUUID().toString(), "android-rust-fixture-one", 1)
                    assertEquals(1L, http.send(source, Outbox(first, 0, false)))
                    assertNull(http.state(source).message)
                    val delivery = http.state(receiver)
                    assertEquals(1L, delivery.revision)
                    assertEquals(1L, delivery.message!!.revision)
                    assertEquals(first.text, http.content(receiver, delivery.message))
                    http.acknowledge(receiver, Ack(first.id, 1, "applied"))
                    assertNull(http.state(receiver).message)
                    receiverControl.ensure(SyncDirection.PUSH_ONLY, false) { true }
                    val second = LocalCopy(UUID.randomUUID().toString(), "android-rust-fixture-two", 2)
                    assertEquals(2L, http.send(source, Outbox(second, 1, false)))
                    assertNull(http.state(receiver).message)
                    assertEquals(410, assertThrows(HttpFailure::class.java) {
                        http.content(receiver, RemoteMessage(second.id, 2, source.state.deviceId))
                    }.status)
                    sourceControl.ensure(SyncDirection.PUSH_ONLY, true) { true }
                    val forbidden = assertThrows(HttpFailure::class.java) {
                        http.send(source, Outbox(LocalCopy(UUID.randomUUID().toString(), "blocked", 3), 2, false))
                    }
                    assertEquals(403, forbidden.status)
                    assertEquals("DIRECTION_FORBIDDEN", forbidden.code)
                    val oldSid = requireNotNull(receiver.control).sessionId
                    receiverControl.revoke()
                    val revoked = assertThrows(HttpFailure::class.java) {
                        http.request("GET", "api/state", http.bearer(receiver), headers = mapOf("X-RCS-Sync-Session" to oldSid))
                    }
                    assertEquals(409, revoked.status)
                    assertEquals("SESSION_REVOKED", revoked.code)
                    if (websocket) {
                        assertTrue("real Rust WSS control was not exercised", wsControls.get() >= 5)
                        assertEquals("WSS mode unexpectedly used HTTP control", 0, httpControls.get())
                    } else assertTrue("real Rust HTTP fallback was not exercised", httpControls.get() >= 5)
                } finally { sourceControl.close(); receiverControl.close() }
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
            for (c in listOf(upstream, client)) { c.connectionPool.evictAll(); c.dispatcher.executorService.shutdownNow() }
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            temp.toFile().deleteRecursively()
        }
    }

    private fun websocketProxy(request: RecordedRequest, upstream: OkHttpClient, port: Int, count: AtomicInteger): MockResponse {
        val opened = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val downstream = AtomicReference<WebSocket?>()
        val peer = upstream.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/api/ws")
            .header("X-RCS-Protocol", requireNotNull(request.getHeader("X-RCS-Protocol")))
            .header("Authorization", requireNotNull(request.getHeader("Authorization"))).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) { downstream.get()?.send(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure.set(t); opened.countDown(); downstream.get()?.cancel() }
        })
        return MockResponse().setHeader("X-RCS-Protocol", "3").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { downstream.set(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!opened.await(5, TimeUnit.SECONDS) || failure.get() != null) { webSocket.cancel(); return }
                count.incrementAndGet()
                peer.send(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { peer.cancel() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { peer.cancel() }
        })
    }
}
