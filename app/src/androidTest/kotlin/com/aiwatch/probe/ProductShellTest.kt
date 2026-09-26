package com.aiwatch.probe

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.probe.product.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ProductShellTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun withStore(test: (ProductStore, File) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "product-test-${java.util.UUID.randomUUID()}")
        try { test(ProductStore(root), root) } finally { root.deleteRecursively() }
    }
    @Test fun profilesPersistIndependentlyAndRejectInvalidInput() = withStore { store, root ->
        store.saveCharacter(" 星野 ")
        store.saveServer("https://example.test/ota")
        val reopened = ProductStore(root)
        assertEquals("星野", reopened.character().name)
        assertEquals("https://example.test/ota", reopened.server().endpoint)
        listOf("", "x".repeat(33), "a\nb").forEach { bad ->
            try { store.saveCharacter(bad); fail("Accepted invalid name") } catch (_: IllegalArgumentException) { }
        }
        listOf("http://example.test", "https://u:p@example.test", "https://example.test/?token=x",
            "https://example.test/#secret", "https://example.test:99999").forEach { bad ->
            assertFalse(ProductStore.validEndpoint(bad))
        }
        store.saveServer("")
        assertEquals("", store.server().endpoint)
        assertEquals("星野", store.character().name)
    }
    @Test fun importedImageIsPrivateBoundedAndIndependentOfOriginal() = withStore { store, root ->
        val bitmap = Bitmap.createBitmap(2048, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val original = File(root, "source.png")
        original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        store.importImage(original.inputStream())
        assertTrue(original.delete())
        val loaded = ProductStore(root).avatar()!!
        assertEquals(1024, loaded.width); assertEquals(8, loaded.height); assertEquals(Color.RED, loaded.getPixel(0, 0))
        loaded.recycle()
        store.saveCharacter("新名字")
        assertNotNull(store.avatar()?.also { it.recycle() })
        store.resetAvatar(); assertNull(store.avatar()); assertEquals("新名字", store.character().name)
    }
    @Test fun rejectedImportPreservesPreviousAvatarAndCleansTemporaryFiles() = withStore { store, root ->
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        store.importImage(ByteArrayInputStream(output.toByteArray()))
        val before = File(root, "avatar.png").readBytes()
        listOf("not an image".toByteArray(), ByteArray(ProductStore.MAX_BYTES + 1)).forEach { invalid ->
            var rejected = false
            try { store.importImage(ByteArrayInputStream(invalid)) } catch (_: Exception) { rejected = true }
            assertTrue(rejected); assertArrayEquals(before, File(root, "avatar.png").readBytes())
            assertTrue(root.listFiles()!!.none { it.name.startsWith("image-") })
        }
    }
    @Test fun homeLaunchIsOfflineAndSettingsAndDiagnosticsRemainReachable() {
        val home = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync {
                val views = flatten(home.window.decorView)
                val talk = views.filterIsInstance<Button>().first { it.text.toString() == home.getString(R.string.product_talk) }
                assertFalse(talk.isEnabled)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == home.getString(R.string.product_offline) })
                views.filterIsInstance<Button>().first { it.text.toString() == home.getString(R.string.product_settings) }.performClick()
            }
            val settings = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull(settings)
            instrumentation.runOnMainSync {
                val labels = flatten(settings.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.contains(settings.getString(R.string.product_live2d)))
                assertTrue(labels.contains(settings.getString(R.string.product_voice)))
                assertTrue(labels.contains(settings.getString(R.string.product_diagnostics)))
                settings.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { home.finish() }
        }
    }
    private fun flatten(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
}
