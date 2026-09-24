package com.aiwatch.protocol

import com.google.gson.JsonParser
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.File
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString

class WebSocketTransportTest {
    @Test fun untrustedTlsCertificateIsRejected() = runBlocking<Unit> {
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val certificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(certificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            WebSocketTransport().use { transport ->
                transport.connect(config(server), DeviceIdentity.generate())
                val failure = assertIs<SocketEvent.Disconnected>(withTimeout(5000) { transport.nextEvent() })
                assertEquals(DisconnectKind.TLS, failure.kind)
                assertEquals(TransportState.DISCONNECTED, transport.state)
                assertNull(transport.playbackConfig)
            }
        }
    }
    @TempDir lateinit var directory: File
    private val hello = """{"type":"hello","transport":"websocket","session_id":"session","audio_params":{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60}}"""
    private fun config(server: MockWebServer) = WebSocketConfig(server.url("/ws").toString().replaceFirst("http", "ws"), "secret")

    @Test fun persistedIdentityBootstrapAndWebSocketFormOneChain() = runBlocking<Unit> {
        val job = SupervisorJob()
        try {
            val identity = DeviceIdentityStore(File(directory, "identity.bin"), CoroutineScope(job + Dispatchers.IO)).getOrCreate()
            MockWebServer().use { server ->
                val url = config(server).url
                server.enqueue(MockResponse().setBody("""{"websocket":{"url":"$url","token":"chain-token"}}"""))
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(ws: WebSocket, text: String) { ws.send(hello) }
                }))
                val bootstrap = assertIs<BootstrapResult.Ready>(BootstrapRepository(allowInsecureDevelopment = true)
                    .fetch(server.url("/ota").toString(), identity, "test"))
                WebSocketTransport(allowInsecureDevelopment = true).use { transport ->
                    transport.connect(bootstrap.websocket, identity)
                    assertIs<ProtocolEvent.Hello>(assertIs<SocketEvent.Message>(withTimeout(3000) { transport.nextEvent() }).event)
                    val ota = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    val ws = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals(ota.getHeader("Device-Id"), ws.getHeader("Device-Id"))
                    assertEquals(ota.getHeader("Client-Id"), ws.getHeader("Client-Id"))
                    assertEquals("Bearer chain-token", ws.getHeader("Authorization"))
                    assertEquals(24000, transport.playbackConfig?.sampleRate)
                }
            }
        } finally { job.cancelAndJoin() }
    }

    @Test fun handshakeHeadersAndBidirectionalMessages() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val received = LinkedBlockingQueue<Any>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    received.add(text)
                    if (JsonParser.parseString(text).asJsonObject["type"].asString == "hello") {
                        ws.send(hello)
                        ws.send("""{"type":"tts","state":"start"}""")
                        ws.send(byteArrayOf(1, 2, 3).toByteString())
                    }
                }
                override fun onMessage(ws: WebSocket, bytes: ByteString) { received.add(bytes) }
            }))
            WebSocketTransport(allowInsecureDevelopment = true).use { transport ->
                assertFalse(transport.sendAudio(byteArrayOf(1)))
                val identity = DeviceIdentity.generate()
                val id = transport.connect(config(server), identity)
                val event = withTimeout(3000) { assertIs<SocketEvent.Message>(transport.nextEvent()) }
                assertEquals(id, event.connectionId)
                assertIs<ProtocolEvent.Hello>(event.event)
                assertEquals(24000, transport.playbackConfig?.sampleRate)
                assertEquals(TransportState.READY, transport.state)
                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("Bearer secret", request.getHeader("Authorization"))
                assertEquals("1", request.getHeader("Protocol-Version"))
                assertEquals(identity.deviceId, request.getHeader("Device-Id"))
                assertEquals(identity.clientId, request.getHeader("Client-Id"))
                val clientHello = JsonParser.parseString(received.poll(2, TimeUnit.SECONDS) as String).asJsonObject
                assertEquals(16000, clientHello["audio_params"].asJsonObject["sample_rate"].asInt)
                assertFalse(clientHello.has("features"))
                assertIs<ProtocolEvent.Tts>((withTimeout(3000) { transport.nextEvent() } as SocketEvent.Message).event)
                assertContentEquals(byteArrayOf(1, 2, 3), assertIs<ProtocolEvent.BinaryAudio>(
                    (withTimeout(3000) { transport.nextEvent() } as SocketEvent.Message).event).bytes)
                assertTrue(transport.listen(true))
                assertTrue(transport.sendAudio(byteArrayOf(4, 5)))
                assertTrue(transport.abort())
                assertEquals("listen", JsonParser.parseString(received.poll(2, TimeUnit.SECONDS) as String).asJsonObject["type"].asString)
                assertEquals(byteArrayOf(4, 5).toByteString(), received.poll(2, TimeUnit.SECONDS))
                assertEquals("abort", JsonParser.parseString(received.poll(2, TimeUnit.SECONDS) as String).asJsonObject["type"].asString)
            }
        }
    }

    @Test fun missingHelloTimesOutAndBlocksSending() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            WebSocketTransport(allowInsecureDevelopment = true, helloTimeoutMs = 200).use { transport ->
                transport.connect(config(server), DeviceIdentity.generate())
                assertEquals("Handshake timeout", assertIs<SocketEvent.Disconnected>(withTimeout(3000) { transport.nextEvent() }).reason)
                assertEquals(TransportState.DISCONNECTED, transport.state)
                assertNull(transport.playbackConfig)
                assertFalse(transport.listen(true))
            }
        }
    }

    @Test fun invalidHelloAndEarlyBinaryFailClosed() = runBlocking<Unit> {
        for (binary in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    if (binary) ws.send(byteArrayOf(1).toByteString()) else ws.send(hello.replace("24000", "32000"))
                }
            }))
            WebSocketTransport(allowInsecureDevelopment = true).use { transport ->
                transport.connect(config(server), DeviceIdentity.generate())
                assertIs<SocketEvent.Disconnected>(withTimeout(3000) { transport.nextEvent() })
                assertNull(transport.playbackConfig)
                assertFalse(transport.sendAudio(byteArrayOf(1)))
            }
        }
    }

    @Test fun explicitReconnectUsesNewGenerationAndClearsPlayback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) { ws.send(hello) }
            })) }
            WebSocketTransport(allowInsecureDevelopment = true).use { transport ->
                val identity = DeviceIdentity.generate()
                val first = transport.connect(config(server), identity)
                withTimeout(3000) { transport.nextEvent() }
                transport.disconnect()
                assertNull(transport.playbackConfig)
                val second = transport.connect(config(server), identity)
                assertTrue(second > first)
                val old = withTimeout(3000) { transport.nextEvent() }
                assertEquals(first, assertIs<SocketEvent.Disconnected>(old).connectionId)
                val current = withTimeout(3000) { transport.nextEvent() }
                assertEquals(second, assertIs<SocketEvent.Message>(current).connectionId)
                assertEquals(TransportState.READY, transport.state)
            }
        }
    }

    @Test fun httpRejectionIsDisconnectedWithoutLeakingResponse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret"))
            WebSocketTransport(allowInsecureDevelopment = true).use { transport ->
                transport.connect(config(server), DeviceIdentity.generate())
                val event = withTimeout(3000) { transport.nextEvent() }
                assertIs<SocketEvent.Disconnected>(event)
                assertFalse(event.toString().contains("secret"))
            }
        }
    }

    @Test fun insecureEndpointAndReuseAfterCloseAreRejected() {
        val transport = WebSocketTransport()
        assertFailsWith<IllegalArgumentException> { transport.connect(WebSocketConfig("ws://localhost/ws", null), DeviceIdentity.generate()) }
        transport.close()
        assertFailsWith<IllegalStateException> { transport.connect(WebSocketConfig("wss://example.test/ws", null), DeviceIdentity.generate()) }
    }
}
