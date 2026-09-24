package com.aiwatch.probe

import com.aiwatch.audio.*
import com.aiwatch.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Foreground debug session only. UI owns start/stop intent; coordinator owns protocol state. */
class DebugAudioSession(private val application: ProbeApplication, endpoint: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = WebSocketTransport()
    private val audioLock = Any()
    @Volatile private var playback: PlaybackQueue? = null
    @Volatile private var playbackConfig: PlaybackAudioConfig? = null
    @Volatile private var capture: AndroidAudioCaptureSource? = null
    @Volatile var audioError: String? = null
        private set
    private val talking = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackWake: Channel<Unit>? = null
    val txPackets = AtomicLong()
    val rxPackets = AtomicLong()
    val readChunks = AtomicLong()
    val discardedTailSamples = AtomicLong()
    private val bootstrap = BootstrapRepository()
    val coordinator = SessionCoordinator(scope, { application.identityStore.getOrCreate() }, {
        bootstrap.fetch(endpoint, it, "phase1c-debug")
    }, transport, onEvent = ::onEvent, flushAudio = ::flush)
    val state get() = coordinator.state
    val output get() = playbackConfig
    val queueDepth get() = playback?.let { "${it.encodedDepth} encoded / ${it.pcmDepth} PCM" } ?: "0"

    init {
        scope.launch {
            state.collect { if (it.phase != SessionPhase.READY) stopTalking() }
        }
    }

    fun connect() { scope.launch { coordinator.start() } }

    // Called from Main thread: one capture job at a time; never start mic just from Connect.
    fun startTalking() {
        if (captureJob?.isActive == true || state.value.phase != SessionPhase.READY) return
        talking.set(true)
        captureJob = scope.launch {
            val source = AndroidAudioCaptureSource(application)
            var generation: Long? = null
            val accumulator = PcmFrameAccumulator(960)
            try {
                val config = playbackConfig ?: return@launch
                val encoder = ConcentusOpusCodec(config)
                capture = source
                source.start()
                if (!talking.get()) return@launch
                generation = coordinator.beginCapture() ?: return@launch
                val buffer = ShortArray(1024)
                while (isActive && talking.get()) {
                    val count = source.read(buffer)
                    if (!talking.get()) break
                    check(count > 0) { "Capture read failed" }
                    readChunks.incrementAndGet()
                    for (frame in accumulator.append(buffer, length = count)) {
                        if (!talking.get()) break
                        check(coordinator.sendAudio(encoder.encodeUplink(frame), generation)) { "Uplink rejected" }
                        txPackets.incrementAndGet()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (talking.get()) {
                    audioError = "Capture/encode/send failed; inspect device support and microphone permission"
                    coordinator.stop()
                }
            } finally {
                // Manual end-of-turn discards only an incomplete tail, explicitly counted; never pad it.
                discardedTailSamples.addAndGet(accumulator.bufferedSamples.toLong())
                talking.set(false)
                runCatching { source.close() }
                capture = null
                generation?.let { withContext(NonCancellable) { runCatching { coordinator.endCapture(it) } } }
            }
        }
    }

    fun stopTalking() { talking.set(false); runCatching { capture?.stop() } }
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
    fun close() {
        stopTalking()
        scope.launch {
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
