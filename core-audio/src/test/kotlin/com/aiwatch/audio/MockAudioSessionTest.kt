package com.aiwatch.audio

import com.aiwatch.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Collections
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import kotlin.math.sin
import kotlin.test.*

class MockAudioSessionTest {
    @Test fun realWebSocketRoutes16kUplinkAnd24kTtsToPcmQueue() = runSession(0)
    @Test fun paddedFinalOpusFrameArrivesBeforeListenStop() = runSession(100)

    private fun runSession(tail: Int) = runBlocking<Unit> {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val uplinkSamples = Channel<Int>(Channel.UNLIMITED)
        val wireOrder = Collections.synchronizedList(mutableListOf<String>())
        val stopped = CompletableDeferred<Unit>()
        val pcm = mutableListOf<Short>()
        var queue: PlaybackQueue? = null
        val ttsEncoder = OpusEncoder(24000, 1, OpusApplication.OPUS_APPLICATION_AUDIO)
        val packets = (0..2).map { frame ->
            val input = ShortArray(1440) { (sin((frame * 1440 + it) * 2.0 * Math.PI * 440 / 24000) * 12000).toInt().toShort() }
            val output = ByteArray(4000)
            output.copyOf(ttsEncoder.encode(input, 0, 1440, output, 0, output.size))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"type\":\"hello\"")) {
                        webSocket.send("""{"type":"hello","transport":"websocket","session_id":"audio-test","audio_params":{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60}}""")
                    } else if (text.contains("\"state\":\"stop\"")) {
                        wireOrder.add("stop")
                        webSocket.send("""{"type":"tts","state":"start"}""")
                        packets.forEach { webSocket.send(it.toByteString()) }
                        webSocket.send("""{"type":"tts","state":"stop"}""")
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        val output = ShortArray(1920)
                        wireOrder.add("audio")
                        uplinkSamples.trySend(OpusDecoder(16000, 1).decode(bytes.toByteArray(), 0, bytes.size,
                            output, 0, output.size, false))
                    } catch (failure: Exception) { uplinkSamples.close(failure) }
                }
            }))
            val transport = WebSocketTransport(allowInsecureDevelopment = true)
            val coordinator = SessionCoordinator(scope, { DeviceIdentity.generate() }, {
                BootstrapResult.Ready(WebSocketConfig(server.url("/ws").toString().replaceFirst("http", "ws"), null))
            }, transport, onEvent = { event, generation ->
                when (event) {
                    is ProtocolEvent.Hello -> {
                        val codec = ConcentusOpusCodec(event.audio)
                        queue = PlaybackQueue(codec::decodeDownlink, object : PcmPlaybackSink {
                            override fun write(data: ShortArray, offset: Int, length: Int): Int {
                                pcm.addAll(data.slice(offset until offset + length)); return length
                            }
                            override fun pauseAndFlush() {}
                            override fun close() {}
                        })
                    }
                    is ProtocolEvent.Tts -> when (event.phase) {
                        ProtocolEvent.Tts.Phase.START -> queue!!.begin(generation)
                        ProtocolEvent.Tts.Phase.STOP -> { queue!!.end(generation); stopped.complete(Unit) }
                        else -> Unit
                    }
                    is ProtocolEvent.BinaryAudio -> check(queue!!.offer(event.bytes, generation))
                    else -> Unit
                }
            }, flushAudio = { queue?.flush() })
            try {
                coordinator.start()
                withTimeout(5000) { while (coordinator.state.value.phase != SessionPhase.READY) delay(5) }
                val generation = assertNotNull(coordinator.beginCapture())
                val encoder = ConcentusOpusCodec(PlaybackAudioConfig("opus", 24000, 1, 60))
                val accumulator = PcmFrameAccumulator(960)
                accumulator.append(ShortArray(960 + tail) { 1000 }).forEach {
                    assertTrue(coordinator.sendAudio(encoder.encodeUplink(it), generation))
                }
                accumulator.finishUtterance()?.let {
                    assertTrue(coordinator.sendAudio(encoder.encodeUplink(it), generation))
                }
                assertNull(accumulator.finishUtterance())
                coordinator.endCapture(generation)
                withTimeout(5000) { stopped.await() }
                val expectedPackets = if (tail == 0) 1 else 2
                repeat(expectedPackets) { assertEquals(960, withTimeout(5000) { uplinkSamples.receive() }) }
                assertEquals(List(expectedPackets) { "audio" } + "stop", wireOrder.toList())
                val playback = assertNotNull(queue)
                while (!playback.idle) playback.pump()
                assertEquals(4320, pcm.size)
                assertTrue(pcm.any { it.toInt() != 0 })
                assertEquals(ConversationState.IDLE, coordinator.state.value.conversation)
            } finally { coordinator.close(); queue?.close(); job.cancelAndJoin() }
        }
    }
}
