package com.aiwatch.probe

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.probe.conversation.ConversationListView
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.MessageAuthor
import com.aiwatch.probe.conversation.MessageBubbleView
import com.aiwatch.probe.character.AvatarStageView
import com.aiwatch.probe.home.CompanionActivity
import com.aiwatch.probe.product.HomeActivity
import com.aiwatch.probe.voice.PushToTalkView
import com.aiwatch.probe.voice.toUiState
import com.aiwatch.protocol.ConversationState as ProtocolConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P0-1 acceptance. Everything is asserted through the public view hierarchy and the Activity's public
 * state, so no test-only hooks are needed in the product code.
 *
 * No @RunWith: the project's AndroidJUnitRunner executes plain JUnit4 classes, matching the existing
 * Phase2B1AAvatarTest.
 */
class CompanionHomeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            result = block()
            latch.countDown()
        }
        assertTrue("main thread did not run the block", latch.await(10, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain { condition() }) return true
            SystemClock.sleep(120)
        }
        println("timed out waiting for: $what")
        return false
    }

    private fun flatten(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()

    /**
     * [scripted] selects the deterministic scripted conversation. It is off by default so the rest of
     * these tests exercise the production voice path (which is expected to stay idle without an endpoint).
     */
    private fun launchCompanion(scripted: Boolean = false): CompanionActivity {
        val intent = Intent(instrumentation.targetContext, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (scripted) intent.putExtra(CompanionActivity.EXTRA_SCRIPTED_CONVERSATION, true)
        return instrumentation.startActivitySync(intent) as CompanionActivity
    }

    private fun pushToTalk(activity: CompanionActivity): PushToTalkView =
        flatten(activity.window.decorView).filterIsInstance<PushToTalkView>().first()

    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
        onMain { view.dispatchTouchEvent(event) }
        event.recycle()
    }

    @Test
    fun companionHomeIsTheLauncherAndShowsCharacterTranscriptAndPushToTalk() {
        // Criterion 1: the launcher resolves to the companion Home, not the Phase 2A probe screen.
        val resolveInfo = instrumentation.targetContext.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(instrumentation.targetContext.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertNotNull("no launcher activity resolved", resolveInfo)
        val launcher = requireNotNull(resolveInfo).activityInfo.name
        println("COMPANION_LAUNCHER $launcher")
        assertEquals(CompanionActivity::class.java.name, launcher)

        val activity = launchCompanion()
        try {
            assertTrue(
                "character stage never appeared",
                waitFor("stage laid out") {
                    flatten(activity.window.decorView).filterIsInstance<AvatarStageView>()
                        .any { it.width > 0 && it.height > 0 }
                },
            )

            val views = onMain { flatten(activity.window.decorView) }
            assertTrue("no character stage", views.any { it is AvatarStageView })
            assertTrue("no transcript", views.any { it is ConversationListView })
            assertTrue("no push-to-talk", views.any { it is PushToTalkView })
            assertTrue("no bubbles", views.any { it is MessageBubbleView })

            // Criterion 2: nothing overflows the panel horizontally.
            val root = onMain { activity.window.decorView }
            val screenWidth = onMain { activity.resources.displayMetrics.widthPixels }
            val widest = onMain { views.maxOf { it.right } }
            println("COMPANION_LAYOUT screenWidth=$screenWidth widestChildRight=$widest")
            assertTrue("content overflows the panel: widest=$widest screen=$screenWidth", widest <= screenWidth)

            // Criterion 3: the greeting is already on screen, so Home never opens empty.
            assertTrue("greeting missing", onMain { activity.conversationMessages.isNotEmpty() })
            println("COMPANION_STATE initial=${onMain { activity.conversationState }} " +
                "messages=${onMain { activity.conversationMessages.size }}")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun pushToTalkDrivesTheFourStatesAndAppendsBothSides() {
        val activity = launchCompanion(scripted = true)
        try {
            assertTrue("stage never laid out", waitFor("stage laid out") {
                flatten(activity.window.decorView).filterIsInstance<AvatarStageView>()
                    .any { it.width > 0 && it.height > 0 }
            })
            val ptt = onMain { pushToTalk(activity) }
            val before = onMain { activity.conversationMessages.size }
            assertEquals(ConversationState.IDLE, onMain { activity.conversationState })

            touch(ptt, MotionEvent.ACTION_DOWN)
            assertTrue(
                "press did not enter LISTENING",
                waitFor("LISTENING") { activity.conversationState == ConversationState.LISTENING },
            )

            touch(ptt, MotionEvent.ACTION_UP)
            assertTrue(
                "release did not enter THINKING",
                waitFor("THINKING") { activity.conversationState == ConversationState.THINKING },
            )
            assertTrue(
                "never reached SPEAKING",
                waitFor("SPEAKING") { activity.conversationState == ConversationState.SPEAKING },
            )
            assertTrue(
                "never returned to IDLE",
                waitFor("IDLE") { activity.conversationState == ConversationState.IDLE },
            )

            val messages = onMain { activity.conversationMessages }
            println("COMPANION_EXCHANGE before=$before after=${messages.size} " +
                "authors=${messages.map { it.author }}")
            assertTrue("user turn was not appended", messages.any { it.author == MessageAuthor.USER })
            assertTrue(
                "companion turn was not appended",
                messages.count { it.author == MessageAuthor.COMPANION } >= 2,
            )
            assertTrue("no user bubble rendered",
                onMain { flatten(activity.window.decorView).filterIsInstance<MessageBubbleView>() }
                    .size >= 2)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    /** Criterion 8: the Phase 2A screen must remain independently startable for Live2D regression. */
    @Test
    fun phase2aHomeRemainsStartable() {
        val intent = Intent(instrumentation.targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val home = instrumentation.startActivitySync(intent) as HomeActivity
        try {
            println("COMPANION_LEGACY_HOME launched=${home.javaClass.name} mode=${home.currentAvatarMode}")
        } finally {
            instrumentation.runOnMainSync { home.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    /**
     * P0-2A, item 4. The protocol's conversation machine has five states and the UI shows four; the
     * mapping is pure and is asserted here rather than inferred from a live socket, so a regression in
     * the INTERRUPTING case cannot hide behind "we had no server to test against".
     */
    @Test
    fun protocolConversationMapsOntoTheFourUiStates() {
        val cases = mapOf(
            ProtocolConversation.IDLE to ConversationState.IDLE,
            ProtocolConversation.LISTENING to ConversationState.LISTENING,
            ProtocolConversation.THINKING to ConversationState.THINKING,
            ProtocolConversation.SPEAKING to ConversationState.SPEAKING,
            // Interrupt path is Speaking -> INTERRUPTING -> LISTENING. Once the session reports
            // INTERRUPTING the companion has stopped talking and the mic is off, so the four-state UI
            // shows LISTENING rather than inventing a fifth visual state.
            ProtocolConversation.INTERRUPTING to ConversationState.LISTENING,
        )
        cases.forEach { (protocol, expected) ->
            val actual = protocol.toUiState()
            println("P02A_STATE_MAP protocol=$protocol ui=$actual")
            assertEquals("mapping for $protocol", expected, actual)
        }
    }

    /**
     * P0-2A, item 9. With no bootstrap endpoint the production path must stay honest: idle, no fabricated
     * user turn, no crash. A real end-to-end run is deliberately NOT simulated here.
     */
    @Test
    fun realVoicePathStaysIdleAndFabricatesNothingWithoutEndpoint() {
        val activity = launchCompanion(scripted = false)
        try {
            assertTrue("stage never laid out", waitFor("stage laid out") {
                flatten(activity.window.decorView).filterIsInstance<AvatarStageView>()
                    .any { it.width > 0 && it.height > 0 }
            })
            // The greeting is local, so Home is not an empty panel even before any connection exists.
            assertTrue("greeting missing", waitFor("greeting") {
                activity.conversationMessages.isNotEmpty()
            })
            val authorsBefore = onMain { activity.conversationMessages.map { it.author } }

            val ptt = onMain { pushToTalk(activity) }
            touch(ptt, MotionEvent.ACTION_DOWN)
            SystemClock.sleep(400)
            touch(ptt, MotionEvent.ACTION_UP)
            SystemClock.sleep(1_500)

            val state = onMain { activity.conversationState }
            val authorsAfter = onMain { activity.conversationMessages.map { it.author } }
            println("P02A_REAL_PATH state=$state authorsBefore=$authorsBefore authorsAfter=$authorsAfter")
            assertEquals("state must not leave IDLE without a session", ConversationState.IDLE, state)
            assertEquals(
                "no user turn may be invented without an endpoint",
                authorsBefore.count { it == MessageAuthor.USER },
                authorsAfter.count { it == MessageAuthor.USER },
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
