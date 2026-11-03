/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Real TLS and WS on loopback, never a user's server or clipboard. */
class SyncDirectionTransportTest {
    private val device = "00000000-0000-4000-8000-000000000001"
    private val sid = "00000000-0000-4000-8000-000000000002"
    private val source = "00000000-0000-4000-8000-000000000003"
    private val session get() = StoredSession("https://example.test", "ab".repeat(32), true, true,
        SyncState(source, device, 0))
    private val operation get() = DirectionOperation(sid, 1, SyncDirection.RECEIVE_ONLY, false, true)
    private fun ack(type: Boolean = false) = JSONObject().put("deviceId", device).put("sessionId", sid)
        .put("version", 1).put("serverTime", 100).put("expiresAt", 200).put("revoked", false).apply {
            if (type) put("type", "SyncDirectionAck")
        }
    private fun fixture(block: (MockWebServer, SyncTransport, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
                block(server, SyncTransport(server.url("/").toString()), OkHttpClient.Builder()
                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            } finally { HttpsURLConnection.setDefaultSSLSocketFactory(previous) }
        }
    }

    @Test fun realWssControlUsesAuthorizationHeaderAndReceivesAck() = fixture { server, http, client ->
        server.enqueue(MockResponse().setHeader("X-RCS-Protocol", "3").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = JSONObject(text)
                assertEquals("SyncDirectionUpdate", j.getString("type"))
                assertEquals("RECEIVE_ONLY", j.getString("direction"))
                webSocket.send(ack(true).toString())
            }
        }))
        SyncDirectionTransport(http, client).use { remote ->
            assertEquals(sid, remote.report(session, operation).sessionId)
        }
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/ws", request.path)
        assertEquals("3", request.getHeader("X-RCS-Protocol"))
        assertEquals("Bearer $device:${session.token}", request.getHeader("Authorization"))
        assertFalse(request.path!!.contains(session.token))
    }

    @Test fun unavailableWssFallsBackToIdenticalHttpsOperation() = fixture { server, http, client ->
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setHeader("X-RCS-Protocol", "3").setBody(ack().toString()))
        SyncDirectionTransport(http, client).use { remote ->
            assertEquals(1L, remote.report(session, operation).version)
        }
        server.takeRequest(2, TimeUnit.SECONDS)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val sent = JSONObject(request.body.readUtf8())
        assertEquals("PUT", request.method)
        assertEquals("/api/devices/$device/sync-direction", request.path)
        assertEquals(sid, sent.getString("sessionId"))
        assertEquals(1L, sent.getLong("version"))
        assertEquals("RECEIVE_ONLY", sent.getString("direction"))
        assertTrue(sent.getBoolean("connect"))
        assertFalse(sent.has("type"))
    }

    @Test fun persistedRevokeRetriesDirectlyOverHttpsWithoutOpeningUnboundWs() = fixture { server, http, client ->
        server.enqueue(MockResponse().setHeader("X-RCS-Protocol", "3").setBody(ack().put("version", 2)
            .put("revoked", true).put("expiresAt", JSONObject.NULL).toString()))
        val revoke = operation.copy(version = 2, paused = true, connect = false, revoke = true)
        SyncDirectionTransport(http, client).use { remote ->
            assertEquals(2L, remote.report(session, revoke).version)
        }
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("DELETE", request.method)
        assertEquals("/api/devices/$device/sync-direction", request.path)
        val sent = JSONObject(request.body.readUtf8())
        assertEquals(sid, sent.getString("sessionId"))
        assertEquals(2L, sent.getLong("version"))
        assertFalse(sent.has("connect"))
    }

    @Test fun oldServerMissingVersionAdvertisementIsRejected() = fixture { server, http, _ ->
        server.enqueue(MockResponse().setBody("{}"))
        val error = assertThrows(HttpFailure::class.java) {
            http.request("GET", "api/devices/$device/sync-direction", http.bearer(session))
        }
        assertEquals(426, error.status)
    }

    @Test fun metadataMayHideDeliveryDespiteNonzeroGlobalRevision() {
        val http = SyncTransport("https://example.test")
        val base = JSONObject().put("epoch", source).put("revision", 12).put("ackRevision", 4)
            .put("messageId", JSONObject.NULL).put("deliveryRevision", JSONObject.NULL)
            .put("sourceDeviceId", JSONObject.NULL).put("size", JSONObject.NULL).put("sensitive", JSONObject.NULL)
        assertNull(http.parseState(base).message)
        val incoming = JSONObject(base.toString()).put("messageId", sid).put("deliveryRevision", 6)
            .put("sourceDeviceId", source).put("size", 0).put("sensitive", false)
        assertEquals(6L, http.parseState(incoming).message!!.revision)
        assertEquals(12L, http.parseState(incoming).revision)
        assertThrows(IllegalArgumentException::class.java) { http.parseState(JSONObject(base.toString()).put("size", 1)) }
    }
}
