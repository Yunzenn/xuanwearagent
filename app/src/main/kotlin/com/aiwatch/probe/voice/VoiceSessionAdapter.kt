package com.aiwatch.probe.voice

/**
 * Seam between the companion UI and the audio path.
 *
 * P0-2A filled this seam: [XiaozhiVoiceSession] is the production implementation and the Home
 * push-to-talk control drives it directly. The interface stays because it is what keeps the UI from
 * knowing about capture, Opus framing or the protocol coordinator, and because an endpoint-less build
 * can be exercised through a substituted implementation.
 */
interface VoiceSessionAdapter {
    /** The user pressed and is holding. */
    fun onCaptureStarted()

    /** The user released; the captured utterance should be submitted. */
    fun onCaptureReleased()

    /** Abandon the turn (screen going away, or barge-in). */
    fun cancel()
}
