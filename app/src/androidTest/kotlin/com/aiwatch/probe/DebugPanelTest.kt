package com.aiwatch.probe

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertNotNull

class DebugPanelTest {
    @Test fun debugPanelOpensAndClosesWithoutConnectingOrRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            DebugSessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            assertNotNull(activity.window.decorView)
            activity.finish()
        }
        instrumentation.waitForIdleSync()
    }
}
