package com.aiwatch.probe

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.audio.ConcentusOpusCodec
import com.aiwatch.protocol.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Opt-in long test. Uses real app session/devices, test-only certificate trust, local HTTPS/WSS. */
class AudioSoakTest {
    @Test fun tenMinuteEmulatorAudioSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val durationMs = InstrumentationRegistry.getArguments().getString("soakMs")?.toLongOrNull()
        assumeTrue("Run explicitly with -e soakMs 600000", durationMs != null)
        require(durationMs!! >= 600000) { "Acceptance run must last at least ten minutes" }
        val application = instrumentation.targetContext.applicationContext as ProbeApplication
        val activity = instrumentation.startActivitySync(Intent(application, DebugSessionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val serverPackets = AtomicLong()
        val serverStops = AtomicLong()
        val serverFrames = AtomicLong()
        val wireLatePackets = AtomicLong()
        val started = SystemClock.elapsedRealtime()
        val cpuStart = android.os.Process.getElapsedCpuTime()
        val output = File(application.filesDir, "emulator-audio-soak.json")
        val report = JSONObject().put("status", "RUNNING").put("api", android.os.Build.VERSION.SDK_INT)
            .put("scope", "emulator only; test server in same process; no audible/real Xiaozhi certification")
        var cycles = 0
        var interrupts = 0
        var maxRssKb = 0L
        var releaseStopMaxMs = 0L
        val byConnection = mutableMapOf<Long, JSONObject>()
        var session: DebugAudioSession? = null
        fun save() {
            session?.let { active ->
                val id = active.state.value.connectionId
                active.playbackMetrics?.let { metrics ->
                    if (id != null) byConnection[id] = JSONObject()
                        .put("encodedPeak", metrics.maxEncodedDepth).put("pcmPeak", metrics.maxPcmDepth)
                        .put("partialWrites", metrics.partialWrites).put("overloads", metrics.overloads)
                        .put("underruns", metrics.underruns).put("writtenSamples", metrics.writtenSamples)
                }
                report.put("txPackets", active.txPackets.get()).put("rxPackets", active.rxPackets.get())
                    .put("paddedFinalFrames", active.paddedFinalFrames.get()).put("paddingSamples", active.paddingSamples.get())
                    .put("discardedTailSamples", active.discardedTailSamples.get())
                    .put("readChunkHistogram", JSONObject(active.readChunkHistogram().mapKeys { it.key.toString() }))
                    .put("audioError", active.audioError ?: JSONObject.NULL)
            }
            val rss = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
            maxRssKb = maxOf(maxRssKb, rss)
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started).put("cycles", cycles)
                .put("interrupts", interrupts).put("maxRssKb", maxRssKb)
                .put("processCpuMs", android.os.Process.getElapsedCpuTime() - cpuStart)
                .put("releaseToServerStopMaxMs", releaseStopMaxMs)
                .put("serverUplinkPackets", serverPackets.get()).put("serverDecodedSamples", serverFrames.get())
                .put("latePacketsAfterStop", wireLatePackets.get())
                .put("playbackByConnection", JSONObject(byConnection.mapKeys { it.key.toString() }))
            output.writeText(report.toString(2))
        }
        fun await(description: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (!predicate()) {
                session?.audioError?.let { error(it) }
                check(SystemClock.elapsedRealtime() < deadline) { "Timeout: $description; ${session?.state?.value}" }
                Thread.sleep(10)
            }
        }
        try {
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/ota" -> MockResponse().setBody("""{"websocket":{"url":"${server.url("/ws").toString().replaceFirst("https", "wss")}"}}""")
                        "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            var accepting = false
                            val codec = ConcentusOpusCodec(PlaybackAudioConfig("opus", 16000, 1, 60))
                            override fun onMessage(ws: WebSocket, text: String) {
                                val message = JSONObject(text)
                                when (message.optString("type")) {
                                    "hello" -> ws.send("""{"type":"hello","transport":"websocket","session_id":"local-soak","audio_params":{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60}}""")
                                    "listen" -> if (message.optString("state") == "start") accepting = true else {
                                        accepting = false; serverStops.incrementAndGet()
                                        ws.send("""{"type":"tts","state":"start"}""")
                                        repeat(5) {
                                            ws.send(codec.encodeUplink(ShortArray(960) { sample ->
                                                (kotlin.math.sin(sample * 2.0 * Math.PI * 440 / 16000) * 8000).toInt().toShort()
                                            }).toByteString())
                                            Thread.sleep(60)
                                        }
                                        ws.send("""{"type":"tts","state":"stop"}""")
                                    }
                                }
                            }
                            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                                if (!accepting) wireLatePackets.incrementAndGet()
                                serverPackets.incrementAndGet()
                                serverFrames.addAndGet(codec.decodeDownlink(bytes.toByteArray()).size.toLong())
                            }
                        })
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val active = DebugAudioSession(application, server.url("/ota").toString(), client)
                session = active
                active.connect()
                while (SystemClock.elapsedRealtime() - started < durationMs) {
                    await("Ready/Idle") { active.state.value.phase == SessionPhase.READY && active.state.value.conversation == ConversationState.IDLE }
                    val beforeTx = active.txPackets.get()
                    instrumentation.runOnMainSync { active.startTalking() }
                    await("capture frames") { active.txPackets.get() >= beforeTx + 3 }
                    Thread.sleep(137)
                    val beforeStops = serverStops.get()
                    val beforePadded = active.paddedFinalFrames.get()
                    val beforeWritten = active.playbackMetrics?.writtenSamples ?: 0
                    val released = SystemClock.elapsedRealtime()
                    instrumentation.runOnMainSync { active.finishTalking(); active.finishTalking() }
                    await("listen stop") { serverStops.get() > beforeStops }
                    releaseStopMaxMs = maxOf(releaseStopMaxMs, SystemClock.elapsedRealtime() - released)
                    assertTrue(active.paddedFinalFrames.get() - beforePadded <= 1)
                    if (cycles % 10 == 9) {
                        await("Speaking with PCM written") { active.state.value.conversation == ConversationState.SPEAKING &&
                            (active.playbackMetrics?.writtenSamples ?: 0) > beforeWritten }
                        val oldId = active.state.value.connectionId
                        save()
                        instrumentation.runOnMainSync { active.interrupt() }
                        await("hard interrupt reconnect") { active.state.value.phase == SessionPhase.READY && active.state.value.connectionId != oldId }
                        interrupts++
                    } else await("TTS stopped") { active.state.value.conversation == ConversationState.IDLE }
                    Thread.sleep(100)
                    cycles++; save()
                    assertNull(active.audioError)
                    assertEquals(0L, wireLatePackets.get())
                }
                assertTrue(active.paddedFinalFrames.get() > 0)
                assertEquals(0L, active.discardedTailSamples.get())
                assertEquals(serverPackets.get() * 960, serverFrames.get())
                report.put("status", "PASS"); save()
                runBlocking { active.close().join() }
            }
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("failure", failure.javaClass.simpleName + ": " + failure.message)
            save(); throw failure
        } finally {
            session?.let { runBlocking { it.close().join() } }
            instrumentation.runOnMainSync { activity.finish() }
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
