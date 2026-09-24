package com.aiwatch.probe

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Host runs twice with force-stop between runs; neither run deletes or resets identity. */
class IdentityProcessTest {
    @Test fun exportPersistedIdentity() = runBlocking<Unit> {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ProbeApplication
        val identity = application.identityStore.getOrCreate()
        File(application.filesDir, "identity-process-evidence.txt").writeText(
            "${identity.deviceId}\n${identity.clientId}\n${android.os.Process.myPid()}\n",
        )
    }
}
