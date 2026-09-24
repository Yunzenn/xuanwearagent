package com.aiwatch.probe

import com.aiwatch.audio.*
import com.aiwatch.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

/** Foreground debug session only. UI owns start/stop intent; coordinator owns protocol state. */
class DebugAudioSession(private val application: ProbeApplication, endpoint: String,
    client: OkHttpClient = OkHttpClient()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = WebSocketTransport(client)
    private val audioLock = Any()
    @Volatile private var playback: PlaybackQueue? = null
    @Volatile private var playbackConfig: PlaybackAudioConfig? = null
    @Volatile private var capture: AndroidAudioCaptureSource? = null
    @Volatile var audioError: String? = null
        private set
    private enum class CaptureState { RECORDING, FINISHING, CANCELLED }
    private val captureState = AtomicReference(CaptureState.CANCELLED)
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackWake: Channel<Unit>? = null
    val txPackets = AtomicLong()
    val rxPackets = AtomicLong()
    val readChunks = AtomicLong()
    val discardedTailSamples = AtomicLong()
    val paddedFinalFrames = AtomicLong()
    val paddingSamples = AtomicLong()
    private val chunks = ConcurrentHashMap<Int, AtomicLong>()
    fun readChunkHistogram(): Map<Int, Long> = chunks.mapValues { it.value.get() }
    val playbackMetrics get() = playback?.metrics
    private val bootstrap = BootstrapRepository(client)
    val coordinator = SessionCoordinator(scope, { application.identityStore.getOrCreate() }, {
        bootstrap.fetch(endpoint, it, "phase1c-debug")
    }, transport, onEvent = ::onEvent, flushAudio = ::flush, onCaptureInvalidated = ::stopTalking)
    val state get() = coordinator.state
    val output get() = playbackConfig
    val queueDepth get() = playback?.let { "${it.encodedDepth} encoded / ${it.pcmDepth} PCM" } ?: "0"

    fun connect() { scope.launch { coordinator.start() } }

    // Called from Main thread: one capture job at a time; never start mic just from Connect.
    fun startTalking() {
        if (captureJob?.isActive == true || state.value.phase != SessionPhase.READY) return
        captureState.set(CaptureState.RECORDING)
        captureJob = scope.launch {
            val source = AndroidAudioCaptureSource(application)
            var generation: Long? = null
            var pendingFinalTail = 0
            val accumulator = PcmFrameAccumulator(960)
            try {
                val config = playbackConfig ?: return@launch
                val encoder = ConcentusOpusCodec(config)
                capture = source
                source.start()
                if (captureState.get() != CaptureState.RECORDING) return@launch
                generation = coordinator.beginCapture() ?: return@launch
                val buffer = ShortArray(1024)
                while (isActive && captureState.get() == CaptureState.RECORDING) {
                    val count = source.read(buffer)
                    if (captureState.get() == CaptureState.CANCELLED) break
                    // stop() may unblock read with no samples. Positive in-flight reads are preserved.
                    if (count <= 0 && captureState.get() == CaptureState.FINISHING) break
                    check(count > 0) { "Capture read failed" }
                    readChunks.incrementAndGet()
                    chunks.getOrPut(count) { AtomicLong() }.incrementAndGet()
                    for (frame in accumulator.append(buffer, length = count)) {
                        if (captureState.get() == CaptureState.CANCELLED) break
                        check(coordinator.sendAudio(encoder.encodeUplink(frame), generation)) { "Uplink rejected" }
                        txPackets.incrementAndGet()
                    }
                }
                if (isActive && captureState.get() == CaptureState.FINISHING) {
                    val tail = accumulator.bufferedSamples
                    accumulator.finishUtterance()?.let { frame ->
                        pendingFinalTail = tail
                        // Coordinator also checks generation: an intervening interrupt cannot replay this frame.
                        if (captureState.get() != CaptureState.CANCELLED) {
                            check(coordinator.sendAudio(encoder.encodeUplink(frame), generation)) { "Final uplink rejected" }
                            txPackets.incrementAndGet()
                            pendingFinalTail = 0
                            paddedFinalFrames.incrementAndGet()
                            paddingSamples.addAndGet((960 - tail).toLong())
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (captureState.get() != CaptureState.CANCELLED) {
                    audioError = "Capture/encode/send failed; inspect device support and microphone permission"
                    coordinator.stop()
                }
            } finally {
                // Only cancellation/error can discard pending PCM. Normal release finalized above.
                discardedTailSamples.addAndGet(accumulator.bufferedSamples.toLong() + pendingFinalTail)
                captureState.set(CaptureState.CANCELLED)
                runCatching { source.close() }
                capture = null
                generation?.let { withContext(NonCancellable) { runCatching { coordinator.endCapture(it) } } }
            }
        }
    }

    fun finishTalking() {
        captureState.compareAndSet(CaptureState.RECORDING, CaptureState.FINISHING)
        runCatching { capture?.stop() }
    }
    fun stopTalking() { captureState.set(CaptureState.CANCELLED); runCatching { capture?.stop() } }
    fun interrupt() { stopTalking(); scope.launch { coordinator.abort() } }

    private fun flush() {
        // beginCapture also flushes previous playback: do not stop the newly starting capture here.
        synchronized(audioLock) { playback?.flush() }
    }

    private fun onEvent(event: ProtocolEvent, generation: Long) {
        when (event) {
            is ProtocolEvent.Hello -> {
                stopTalking()
                val codec = ConcentusOpusCodec(event.audio)
                val queue = PlaybackQueue(codec::decodeDownlink, AndroidPcmPlaybackSink(event.audio))
                val wake = Channel<Unit>(Channel.CONFLATED)
                synchronized(audioLock) {
                    playbackJob?.cancel(); playbackWake?.close(); playback?.close()
                    playbackWake = wake
                    playbackConfig = event.audio; playback = queue
                }
                playbackJob = scope.launch {
                    try {
                        while (isActive) {
                            wake.receive()
                            while (!queue.idle) { queue.pump(); delay(5) }
                        }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        audioError = "Decode/playback failed"
                        coordinator.stop()
                    }
                }
            }
            is ProtocolEvent.Tts -> when (event.phase) {
                ProtocolEvent.Tts.Phase.START -> playback?.begin(generation)
                ProtocolEvent.Tts.Phase.STOP -> playback?.end(generation)
                else -> Unit
            }
            is ProtocolEvent.BinaryAudio -> {
                check(playback?.offer(event.bytes, generation) == true) { "Playback backpressure or stale generation" }
                playbackWake?.trySend(Unit)
                rxPackets.incrementAndGet()
            }
            else -> Unit
        }
    }

    /** Called on foreground loss. No background recording or reconnect survives this session. */
    fun close(): Job {
        stopTalking()
        return scope.launch {
            try {
                coordinator.close()
                captureJob?.cancelAndJoin(); playbackJob?.cancelAndJoin()
            } finally {
                synchronized(audioLock) { playbackWake?.close(); playback?.close(); playback = null; playbackConfig = null }
                scope.cancel()
            }
        }
    }
}
