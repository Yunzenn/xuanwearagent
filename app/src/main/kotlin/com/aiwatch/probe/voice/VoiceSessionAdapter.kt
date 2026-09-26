package com.aiwatch.probe.voice

/**
 * Seam between the P0-1 UI and the real audio path.
 *
 * P0-1 deliberately captures nothing: the brief forbids touching `core-audio` this round, and the button
 * only drives the scripted conversation. P0-2 replaces [ScriptedVoiceSessionAdapter] with an adapter over
 * `core-audio` + `core-protocol`; the UI calls in this interface never change.
 */
interface VoiceSessionAdapter {
    /** The user pressed and is holding. */
    fun onCaptureStarted()

    /** The user released; capture should be submitted for transcription. */
    fun onCaptureReleased()

    /** Abandon the turn (screen going away, user backed out). */
    fun cancel()
}

/**
 * P0-1 implementation: records nothing, submits nothing, and says so in the log so a capture-less build is
 * never mistaken for a working microphone.
 */
class ScriptedVoiceSessionAdapter : VoiceSessionAdapter {

    override fun onCaptureStarted() {
        android.util.Log.i(TAG, "capture start requested; P0-1 records nothing (see VoiceSessionAdapter)")
    }

    override fun onCaptureReleased() {
        android.util.Log.i(TAG, "capture release requested; P0-1 records nothing (see VoiceSessionAdapter)")
    }

    override fun cancel() = Unit

    private companion object { const val TAG = "VoiceSession" }
}
