package com.aiwatch.protocol

/** Platform-independent events; JSON is decoded at the protocol boundary. */
sealed interface ProtocolEvent {
    data class Hello(
        val sessionId: String,
        val audio: PlaybackAudioConfig,
    ) : ProtocolEvent

    data class Stt(val text: String) : ProtocolEvent
    data class Tts(val phase: Phase, val text: String? = null) : ProtocolEvent {
        enum class Phase { START, SENTENCE_START, STOP }
    }
    data class Llm(val text: String, val emotion: String? = null) : ProtocolEvent
    data class Mcp(val payload: String) : ProtocolEvent
    data class Unknown(val type: String) : ProtocolEvent
    data class BinaryAudio(val bytes: ByteArray) : ProtocolEvent
    data class Error(val message: String, val cause: Throwable? = null) : ProtocolEvent
}

data class PlaybackAudioConfig(
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val frameDurationMs: Int,
)

enum class TransportState { DISCONNECTED, CONNECTING, WAITING_HELLO, READY }
enum class ConversationState { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTING }
