package com.aiwatch.probe

import com.aiwatch.audio.*
import com.aiwatch.protocol.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.StateFlow
import com.aiwatch.probe.voice.SessionCaption
import com.aiwatch.probe.voice.XiaozhiVoiceSession

/**
 * Foreground debug session.
 *
 * P0-2A turned the real implementation into [XiaozhiVoiceSession] so the product Home and the debug
 * screen run the same capture / protocol / playback code. This class now only delegates: it exists so
 * `DebugSessionActivity`, the ten-minute emulator soak evidence and the codec tests keep working, and so
 * there is never a second copy of the audio path.
 *
 * New code should depend on [XiaozhiVoiceSession] directly.
 */
class DebugAudioSession(
    application: ProbeApplication,
    endpoint: String,
    client: OkHttpClient = OkHttpClient(),
) {
    private val session = XiaozhiVoiceSession(application, endpoint, client)

    val caption: StateFlow<SessionCaption> get() = session.caption
    val audioError: String? get() = session.audioError
    val txPackets: AtomicLong get() = session.txPackets
    val rxPackets: AtomicLong get() = session.rxPackets
    val readChunks: AtomicLong get() = session.readChunks
    val discardedTailSamples: AtomicLong get() = session.discardedTailSamples
    val paddedFinalFrames: AtomicLong get() = session.paddedFinalFrames
    val paddingSamples: AtomicLong get() = session.paddingSamples

    fun readChunkHistogram(): Map<Int, Long> = session.readChunkHistogram()
    val playbackMetrics get() = session.playbackMetrics

    val coordinator: SessionCoordinator get() = session.coordinator
    val state get() = session.state
    val output get() = session.output
    val queueDepth: String get() = session.queueDepth

    fun connect() = session.connect()
    fun startTalking() = session.startTalking()
    fun finishTalking() = session.finishTalking()
    fun stopTalking() = session.stopTalking()
    fun interrupt() = session.interrupt()
    fun close(): Job = session.close()
}
