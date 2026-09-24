package com.aiwatch.protocol

/** Call from one serialized event loop. Each socket callback retains its connection ID. */
class TransportStateMachine {
    var state = TransportState.DISCONNECTED
        private set
    var connectionId = 0L
        private set
    var hello: ProtocolEvent.Hello? = null
        private set

    fun connect(): Long {
        check(state == TransportState.DISCONNECTED)
        connectionId++
        state = TransportState.CONNECTING
        return connectionId
    }

    fun opened(id: Long): Boolean {
        if (id != connectionId || state != TransportState.CONNECTING) return false
        state = TransportState.WAITING_HELLO
        return true
    }

    fun hello(id: Long, event: ProtocolEvent.Hello): Boolean {
        if (id != connectionId || state != TransportState.WAITING_HELLO) return false
        hello = event
        state = TransportState.READY
        return true
    }

    /** Also used for hello timeout and socket failure. */
    fun disconnected(id: Long): Boolean {
        if (id != connectionId) return false
        state = TransportState.DISCONNECTED
        hello = null
        return true
    }
}

/** Pure lifecycle logic. Local audio must be flushed before interruptComplete(). */
class ConversationStateMachine {
    var state = ConversationState.IDLE
        private set
    var generation = 0L
        private set
    private var ttsActive = false

    fun startListening() {
        check(state == ConversationState.IDLE)
        generation++
        ttsActive = false
        state = ConversationState.LISTENING
    }

    fun stopListening() {
        check(state == ConversationState.LISTENING)
        state = ConversationState.THINKING
    }

    fun ttsStarted(): Boolean {
        if (state != ConversationState.THINKING) return false
        ttsActive = true
        state = ConversationState.SPEAKING
        return true
    }

    fun acceptsAudio(queuedGeneration: Long): Boolean =
        queuedGeneration == generation && ttsActive && state == ConversationState.SPEAKING

    fun ttsStopped() {
        if (!ttsActive) return
        ttsActive = false
        state = ConversationState.IDLE
    }

    fun interrupt() {
        check(state == ConversationState.SPEAKING || state == ConversationState.THINKING)
        generation++
        ttsActive = false
        state = ConversationState.INTERRUPTING
    }

    fun interruptComplete() {
        check(state == ConversationState.INTERRUPTING)
        state = ConversationState.LISTENING
    }

    fun disconnected() {
        generation++
        ttsActive = false
        state = ConversationState.IDLE
    }
}
