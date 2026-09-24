package com.aiwatch.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select

enum class SessionPhase { STOPPED, IDENTITY, BOOTSTRAP, ACTIVATING, CONNECTING, READY, RETRY_WAIT, AUTH_REQUIRED, ERROR }
data class SessionSnapshot(
    val phase: SessionPhase = SessionPhase.STOPPED,
    val connectionId: Long? = null,
    val generation: Long = 0,
    val retry: Int = 0,
    val diagnostic: String? = null,
    val activationCode: String? = null,
    val conversation: ConversationState = ConversationState.IDLE,
) {
    override fun toString() = "SessionSnapshot(phase=$phase, generation=$generation, retry=$retry)"
}

/** Sole event owner. One actor serializes state; bootstrap/receive/timers only post tagged commands.
 * Audio sink must synchronously consume or tag queues and implement the flush contract before 1C.
 */
class SessionCoordinator(
    private val scope: CoroutineScope,
    private val identity: suspend () -> DeviceIdentity,
    private val bootstrap: suspend (DeviceIdentity) -> BootstrapResult,
    private val transport: SessionTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val activationPolicy: ActivationPolicy = ActivationPolicy(),
    private val interruptPolicy: InterruptPolicy = HardInterruptPolicy,
    private val onEvent: (ProtocolEvent, Long) -> Unit = { _, _ -> },
    private val flushAudio: () -> Unit = {},
    private val onCaptureInvalidated: () -> Unit = {},
) {
    private sealed interface Command {
        data object Start : Command
        data class Stop(val done: CompletableDeferred<Unit>) : Command
        data class Boot(val epoch: Long, val id: DeviceIdentity, val result: BootstrapResult) : Command
        data class Identified(val epoch: Long, val id: DeviceIdentity) : Command
        data class Failed(val epoch: Long, val message: String) : Command
        data class Inbound(val epoch: Long, val event: SocketEvent) : Command
        data class Retry(val epoch: Long) : Command
        data class Stable(val epoch: Long, val connectionId: Long) : Command
        data class Listen(val start: Boolean, val generation: Long? = null) : Command
        data class BeginCapture(val done: CompletableDeferred<Long?>) : Command
        data class Audio(val generation: Long, val bytes: ByteArray, val done: CompletableDeferred<Boolean>) : Command
        data object Abort : Command
        data class Shutdown(val done: CompletableDeferred<Unit>) : Command
    }
    private val commands = Channel<Command>(64)
    private val mutableState = MutableStateFlow(SessionSnapshot())
    val state = mutableState.asStateFlow()
    private val conversation = ConversationStateMachine()
    private var epoch = 0L
    private var currentConnection: Long? = null
    private var generation = 0L
    private var retries = 0
    private var authRefreshes = 0
    private var activationPolls = 0
    private var active = false
    private var worker: Job? = null
    private var receiver: Job? = null
    private var stabilityTimer: Job? = null
    private val actor = scope.launch {
        try {
            for (command in commands) when (command) {
                Command.Start -> if (!active) {
                    active = true; retries = 0; authRefreshes = 0; activationPolls = 0
                    beginBootstrap()
                }
                is Command.Stop -> { stopInternal(); command.done.complete(Unit) }
                is Command.Shutdown -> {
                    stopInternal(); transport.close(); commands.close(); command.done.complete(Unit); break
                }
                is Command.Boot -> if (active && command.epoch == epoch) handleBootstrap(command)
                is Command.Identified -> if (active && command.epoch == epoch) {
                    publish(SessionPhase.BOOTSTRAP)
                    worker = scope.launch {
                        try { commands.send(Command.Boot(command.epoch, command.id, bootstrap(command.id))) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { commands.send(Command.Failed(command.epoch, "Bootstrap unavailable")) }
                    }
                }
                is Command.Failed -> if (active && command.epoch == epoch) fail(command.message)
                is Command.Retry -> if (active && command.epoch == epoch) beginBootstrap()
                is Command.Stable -> if (active && command.epoch == epoch &&
                    command.connectionId == currentConnection && mutableState.value.phase == SessionPhase.READY) {
                    retries = 0
                    publish(SessionPhase.READY)
                }
                is Command.Inbound -> if (active && command.epoch == epoch && command.event.connectionId == currentConnection) handleEvent(command.event)
                is Command.Listen -> if (mutableState.value.phase == SessionPhase.READY &&
                    (command.generation == null || command.generation == generation)) {
                    if (command.start) beginListening()
                    else if (conversation.state == ConversationState.LISTENING && transport.listen(false)) {
                        conversation.stopListening(); publish(SessionPhase.READY)
                    }
                }
                is Command.BeginCapture -> command.done.complete(beginListening())
                is Command.Audio -> command.done.complete(
                    active && mutableState.value.phase == SessionPhase.READY && command.generation == generation &&
                        conversation.state == ConversationState.LISTENING && transport.sendAudio(command.bytes))
                Command.Abort -> if (mutableState.value.phase == SessionPhase.READY) {
                    interruptPolicy.interrupt(object : InterruptContext {
                        override fun invalidateAndFlushAudio() = invalidateAudio()
                        override fun sendAbort() { transport.abort() }
                        override fun reconnect() = beginBootstrap()
                    })
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            active = false; currentConnection = null; generation++
            publish(SessionPhase.ERROR, "Session consumer or lifecycle failure")
        } finally {
            worker?.cancel(); receiver?.cancel(); stabilityTimer?.cancel()
            runCatching { onCaptureInvalidated() }
            runCatching { flushAudio() }
            runCatching { transport.close() }; commands.close()
        }
    }

    suspend fun start() { commands.send(Command.Start) }
    suspend fun stop() {
        if (!actor.isActive) return
        val done = CompletableDeferred<Unit>(); commands.send(Command.Stop(done)); awaitAcknowledgement(done)
    }
    suspend fun close() {
        if (!actor.isActive) return
        val done = CompletableDeferred<Unit>(); commands.send(Command.Shutdown(done)); awaitAcknowledgement(done); actor.join()
    }
    private suspend fun awaitAcknowledgement(done: CompletableDeferred<Unit>) {
        select<Unit> { done.onAwait { }; actor.onJoin { } }
    }
    suspend fun listen(start: Boolean) { commands.send(Command.Listen(start)) }
    suspend fun endCapture(generation: Long) { commands.send(Command.Listen(false, generation)) }
    suspend fun beginCapture(): Long? {
        val done = CompletableDeferred<Long?>()
        commands.send(Command.BeginCapture(done))
        return select { done.onAwait { it }; actor.onJoin { null } }
    }
    suspend fun sendAudio(bytes: ByteArray, generation: Long): Boolean {
        require(bytes.isNotEmpty() && bytes.size <= 65536)
        val done = CompletableDeferred<Boolean>()
        commands.send(Command.Audio(generation, bytes.copyOf(), done))
        return select { done.onAwait { it }; actor.onJoin { false } }
    }
    suspend fun abort() { commands.send(Command.Abort) }

    private fun publish(phase: SessionPhase, diagnostic: String? = null, code: String? = null) {
        mutableState.value = SessionSnapshot(phase, currentConnection, generation, retries, diagnostic, code, conversation.state)
    }
    private fun beginListening(): Long? {
        if (!active || mutableState.value.phase != SessionPhase.READY || conversation.state != ConversationState.IDLE) return null
        // Discard any previous turn's device-side playback before capturing the next turn.
        flushAudio()
        if (!transport.listen(true)) return null
        generation++; conversation.startListening(); publish(SessionPhase.READY)
        return generation
    }
    private fun invalidateAudio() {
        generation++; conversation.disconnected(); onCaptureInvalidated(); flushAudio()
    }
    private fun retire() {
        epoch++; worker?.cancel(); receiver?.cancel(); stabilityTimer?.cancel(); currentConnection = null
        invalidateAudio(); transport.disconnect()
    }
    private fun stopInternal() { active = false; retire(); publish(SessionPhase.STOPPED) }
    private fun fail(reason: String, phase: SessionPhase = SessionPhase.ERROR) {
        active = false; retire(); publish(phase, reason)
    }
    private fun beginBootstrap() {
        retire(); publish(SessionPhase.IDENTITY)
        val attemptEpoch = epoch
        worker = scope.launch {
            try {
                val id = identity()
                commands.send(Command.Identified(attemptEpoch, id))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { commands.send(Command.Failed(attemptEpoch, "Identity/bootstrap unavailable; identity not reset")) }
        }
    }
    private fun handleBootstrap(command: Command.Boot) {
        when (val result = command.result) {
            is BootstrapResult.Ready -> {
                activationPolls = 0
                try { currentConnection = transport.connect(result.websocket, command.id) }
                catch (_: Exception) { fail("Invalid transport configuration"); return }
                publish(SessionPhase.CONNECTING)
                val receiveEpoch = epoch
                receiver = scope.launch {
                    try { while (isActive) commands.send(Command.Inbound(receiveEpoch, transport.nextEvent())) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { commands.send(Command.Failed(receiveEpoch, "Transport event stream unavailable")) }
                }
            }
            is BootstrapResult.ActivationRequired -> {
                if (result.activation.code.isNullOrBlank()) { fail("Unsupported activation challenge: no binding code"); return }
                if (++activationPolls > activationPolicy.maxPolls) { fail("Activation polling exhausted"); return }
                publish(SessionPhase.ACTIVATING, code = result.activation.code)
                val pollEpoch = epoch
                worker = scope.launch {
                    delay(activationPolicy.pollIntervalMs)
                    try { commands.send(Command.Boot(pollEpoch, command.id, bootstrap(command.id))) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { commands.send(Command.Failed(pollEpoch, "Activation polling failed")) }
                }
            }
            is BootstrapResult.Invalid -> fail("Invalid bootstrap response")
            is BootstrapResult.Failure -> {
                if (result.httpStatus in listOf(401, 403)) fail("Bootstrap authorization required", SessionPhase.AUTH_REQUIRED)
                else if (result.kind == BootstrapResult.Failure.Kind.NETWORK || (result.httpStatus ?: 0) >= 500) scheduleRetry()
                else fail("Bootstrap HTTP failure")
            }
        }
    }
    private fun scheduleRetry() {
        retire()
        if (++retries > retryPolicy.maxRetries) { fail("Retry budget exhausted"); return }
        publish(SessionPhase.RETRY_WAIT)
        val retryEpoch = epoch
        worker = scope.launch { delay(retryPolicy.delayMs(retries)); commands.send(Command.Retry(retryEpoch)) }
    }
    private fun handleEvent(event: SocketEvent) {
        when (event) {
            is SocketEvent.Disconnected -> when (event.kind) {
                DisconnectKind.NETWORK -> scheduleRetry()
                DisconnectKind.AUTH -> if (authRefreshes++ == 0) beginBootstrap() else fail("Authorization required", SessionPhase.AUTH_REQUIRED)
                DisconnectKind.LOCAL, DisconnectKind.NORMAL -> stopInternal()
                else -> fail("Terminal transport failure: ${event.kind}")
            }
            is SocketEvent.Message -> when (val payload = event.event) {
                is ProtocolEvent.Hello -> {
                    // Consumers must finish configuring playback before UI/capture can observe Ready.
                    onEvent(payload, generation)
                    publish(SessionPhase.READY)
                    stabilityTimer?.cancel()
                    val stableEpoch = epoch
                    val stableConnection = event.connectionId
                    stabilityTimer = scope.launch {
                        delay(retryPolicy.stableConnectionMs)
                        commands.send(Command.Stable(stableEpoch, stableConnection))
                    }
                }
                is ProtocolEvent.BinaryAudio -> if (conversation.acceptsAudio(conversation.generation)) onEvent(payload, generation)
                is ProtocolEvent.Tts -> when (payload.phase) {
                    ProtocolEvent.Tts.Phase.START -> if (conversation.ttsStarted()) { publish(SessionPhase.READY); onEvent(payload, generation) }
                    ProtocolEvent.Tts.Phase.STOP -> if (conversation.state == ConversationState.SPEAKING) { conversation.ttsStopped(); publish(SessionPhase.READY); onEvent(payload, generation) }
                    ProtocolEvent.Tts.Phase.SENTENCE_START -> if (conversation.state == ConversationState.SPEAKING) onEvent(payload, generation)
                }
                is ProtocolEvent.Error -> fail("Protocol error")
                else -> onEvent(payload, generation)
            }
        }
    }
}
