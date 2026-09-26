package com.aiwatch.probe.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Window
import com.aiwatch.probe.character.AvatarProviders
import com.aiwatch.probe.character.CharacterProfile
import com.aiwatch.probe.conversation.ConversationController
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.MessageItem
import com.aiwatch.probe.product.SettingsActivity
import com.aiwatch.probe.voice.ScriptedVoiceSessionAdapter
import com.aiwatch.probe.voice.VoiceSessionAdapter

/**
 * P0-1 launcher: the wrist companion's Home.
 *
 * Thin by design. It owns the profile, a [ConversationController] and a [VoiceSessionAdapter], and does
 * nothing but forward between them and [CompanionHomeView] — the brief explicitly rules out growing
 * another oversized Activity.
 *
 * The Phase 2A [com.aiwatch.probe.product.HomeActivity] is untouched and still startable; it remains the
 * regression entry point for the Live2D fallback.
 */
class CompanionActivity : Activity(), ConversationController.Listener {

    private lateinit var home: CompanionHomeView
    private lateinit var controller: ConversationController

    /** P0-1 records nothing; swapping this for the core-audio adapter is the whole of P0-2's UI change. */
    private val voiceSession: VoiceSessionAdapter = ScriptedVoiceSessionAdapter()

    private val profile: CharacterProfile = CharacterProfile.developmentDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        controller = ConversationController(this)
        home = CompanionHomeView(this).apply {
            onPushToTalkStart = {
                voiceSession.onCaptureStarted()
                controller.onPushToTalkPressed()
            }
            onPushToTalkEnd = {
                controller.onPushToTalkReleased()
                voiceSession.onCaptureReleased()
            }
            onSettingsClick = { startActivity(Intent(this@CompanionActivity, SettingsActivity::class.java)) }
        }

        home.bind(profile, AvatarProviders.forSource(profile.avatar))
        // Opening state: a greeting already on screen, so Home never looks empty on first launch.
        controller.seedGreeting()
        setContentView(home)
    }

    override fun onMessagesChanged(messages: List<MessageItem>) {
        home.renderMessages(messages)
    }

    override fun onStateChanged(state: ConversationState) {
        home.renderState(state)
    }

    override fun onPause() {
        // Drop any queued script step so a backgrounded Home cannot pop back mid-exchange.
        controller.cancel()
        voiceSession.cancel()
        home.detach()
        super.onPause()
    }

    /** For the P0-1 instrumentation assertions. */
    val conversationState: ConversationState get() = controller.state
    val conversationMessages: List<MessageItem> get() = controller.messages
}
