package com.aiwatch.probe.conversation

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Drives the P0-1 scripted conversation.
 *
 * Deliberately NOT a state-machine framework: the brief asks for a scripted loop that exercises the UI,
 * and a reducer/actor layer here would be scaffolding around four transitions. P0-2 replaces the internals
 * of [onPushToTalkReleased] with the real capture/submit/playback path and leaves the callbacks alone.
 *
 * Sequence on release:
 *   LISTENING -> append user line -> THINKING -> append reply -> SPEAKING -> IDLE
 */
class ConversationController(
    private val listener: Listener,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    interface Listener {
        fun onMessagesChanged(messages: List<MessageItem>)
        fun onStateChanged(state: ConversationState)
    }

    private val transcript = mutableListOf<MessageItem>()
    private var nextId = 1L

    var state: ConversationState = ConversationState.IDLE
        private set

    val messages: List<MessageItem> get() = transcript.toList()

    /** True while the scripted exchange is in flight, so a stray release cannot double-fire. */
    private var exchangeInFlight = false

    fun onPushToTalkPressed() {
        if (state != ConversationState.IDLE) return
        transition(ConversationState.LISTENING)
    }

    /**
     * Opening line, so first launch shows a companion who has already spoken rather than an empty panel.
     * No API call is involved in P0-1; P0-2 will replace this with a server greeting.
     */
    fun seedGreeting() {
        if (transcript.isNotEmpty()) return
        append(MessageItem(nextId++, MessageAuthor.COMPANION, GREETING, now()))
    }

    fun onPushToTalkReleased() {
        if (state != ConversationState.LISTENING || exchangeInFlight) return
        exchangeInFlight = true

        append(MessageItem(nextId++, MessageAuthor.USER, nextUserLine(), now()))
        transition(ConversationState.THINKING)
        val pendingId = nextId++
        append(MessageItem(pendingId, MessageAuthor.COMPANION, "", now(), pending = true))

        mainHandler.postDelayed({
            replacePending(pendingId, nextCompanionLine())
            transition(ConversationState.SPEAKING)
            mainHandler.postDelayed({
                transition(ConversationState.IDLE)
                exchangeInFlight = false
            }, SPEAKING_MS)
        }, THINKING_MS)
    }

    /** Called when the host goes away; drops any queued script steps. */
    fun cancel() {
        mainHandler.removeCallbacksAndMessages(null)
        exchangeInFlight = false
        if (state != ConversationState.IDLE) transition(ConversationState.IDLE)
    }

    private fun transition(next: ConversationState) {
        if (state == next) return
        state = next
        Log.i(TAG, "state=$next messages=${transcript.size}")
        listener.onStateChanged(next)
    }

    private fun append(item: MessageItem) {
        transcript += item
        listener.onMessagesChanged(messages)
    }

    private fun replacePending(id: Long, text: String) {
        val index = transcript.indexOfFirst { it.id == id }
        if (index < 0) return
        transcript[index] = transcript[index].copy(text = text, pending = false)
        listener.onMessagesChanged(messages)
    }

    private fun now(): Long = System.currentTimeMillis()

    private var userLineIndex = 0
    private var companionLineIndex = 0

    private fun nextUserLine(): String = SCRIPTED_USER_LINES[userLineIndex++ % SCRIPTED_USER_LINES.size]

    private fun nextCompanionLine(): String =
        SCRIPTED_COMPANION_LINES[companionLineIndex++ % SCRIPTED_COMPANION_LINES.size]

    companion object {
        private const val TAG = "CompanionChat"
        private const val THINKING_MS = 900L
        private const val SPEAKING_MS = 1100L

        private const val GREETING = "今天也辛苦啦。"

        /**
         * P0-1 has no microphone: the "user" turn is scripted so the transcript and the four states can be
         * verified end to end. Every one of these is replaced by real ASR output in P0-2.
         */
        private val SCRIPTED_USER_LINES = listOf(
            "今天有点累",
            "陪我聊两句好吗",
            "外面在下雨",
        )

        private val SCRIPTED_COMPANION_LINES = listOf(
            "那今天就慢一点，也很好。",
            "我在呢，你说，我听着。",
            "下雨天最适合待在屋子里了。",
        )
    }
}
