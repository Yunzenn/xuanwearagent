package com.aiwatch.probe

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.probe.product.HomeActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 2B-1A acceptance: DEV-ONLY Home Live2D integration.
 *
 * Completion is judged only by these five things, and every one of them is asserted here against a real
 * device rather than inferred from a successful compile or install:
 *   1. Home shows a legal Live2D model (verified by reading pixels back off the GL surface).
 *   2. Returning to the static avatar works.
 *   3. A Live2D initialisation failure falls back automatically.
 *   4. background/resume with surface recreation does not crash.
 *   5. The shader resources are actually packaged and readable on device.
 */
class Phase2B1AAvatarTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private companion object {
        /** Kept in step with the framework's standardES directory; asserted, not assumed. */
        const val SHADER_DIR = "com/live2d/sdk/cubism/framework/shaders/standardES"
        const val EXPECTED_SHADERS = 36
        const val WAIT_MS = 30_000L
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(description: String, timeoutMs: Long = WAIT_MS, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        val ok = condition()
        if (!ok) println("waitFor timed out: $description")
        return ok
    }

    private fun launchHome(
        forceFailure: Boolean = false,
        harnessHost: Boolean = false,
        officialBackend: Boolean = false,
        plainShaderProbe: Boolean = false,
    ): HomeActivity {
        val intent = Intent(instrumentation.targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (forceFailure) intent.putExtra(HomeActivity.EXTRA_FORCE_LIVE2D_FAILURE, true)
        if (harnessHost) intent.putExtra(HomeActivity.EXTRA_HOST_MODE_HARNESS, true)
        if (officialBackend) intent.putExtra(HomeActivity.EXTRA_RUNTIME_BACKEND_OFFICIAL, true)
        if (plainShaderProbe) intent.putExtra(HomeActivity.EXTRA_PLAIN_SHADER_PROBE, true)
        return instrumentation.startActivitySync(intent) as HomeActivity
    }

    /**
     * Infrastructure calibration, not a P2B-1A acceptance criterion.
     *
     * Draws a Live2D-independent magenta triangle (trivial shader, positions already in NDC) into the same
     * framebuffer the product path uses, then reads that same frame back twice: `glReadPixels` on the GL
     * thread and PixelCopy on the presented surface. Earlier black-frame conclusions rested on
     * `glReadPixels` alone, so this is what makes them trustworthy — or invalidates them.
     */
    @Test fun plainShaderProbeCalibratesBothReadbacks() {
        val home = launchHome(harnessHost = true, plainShaderProbe = true)
        try {
            assertTrue(
                "runtime never became ready",
                waitFor("runtime ready") {
                    onMain { home.currentAvatarMode } == HomeActivity.AvatarMode.LIVE2D
                },
            )
            assertTrue(
                "surface not laid out",
                waitFor("surface laid out") {
                    live2dSurface(home)?.let { it.width > 0 && it.height > 0 } == true
                },
            )

            val glRead = waitFor("probe produced a result") {
                onMain { home.live2DPlainShaderProbeResult } != null
            }
            val probeText = onMain { home.live2DPlainShaderProbeResult }
            val glReadPass = probeText?.contains("outcome=PASS") == true
            println("PLAINSHADER_GLREADPIXELS pass=$glReadPass reported=$glRead detail=$probeText")

            // captureAfterFrames retries: a single PixelCopy.request races the surface's first buffer and
            // returns ERROR_SOURCE_NO_DATA (code 3), which is not a rendering result.
            val bitmap = captureAfterFrames(home)
            assertNotNull("could not read the presented surface back", bitmap)
            val magenta = countMagenta(bitmap!!)
            val pixelCopyPass = magenta > 0
            println(
                "PLAINSHADER_PIXELCOPY pass=$pixelCopyPass magentaPixels=$magenta " +
                    "size=${bitmap.width}x${bitmap.height}",
            )
            println(
                "PLAINSHADER_MATRIX glReadPixels=$glReadPass pixelCopy=$pixelCopyPass " +
                    "verdict=${verdict(glReadPass, pixelCopyPass)}",
            )
            bitmap.recycle()

            assertTrue("glReadPixels saw no magenta: $probeText", glReadPass)
            assertTrue("PixelCopy saw no magenta on the presented surface", pixelCopyPass)
        } finally {
            instrumentation.runOnMainSync { home.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun verdict(glReadPass: Boolean, pixelCopyPass: Boolean): String = when {
        glReadPass && pixelCopyPass -> "HOST_AND_READBACKS_OK"
        glReadPass -> "SURFACE_OR_COMPOSITION_PROBLEM"
        pixelCopyPass -> "GLREADPIXELS_UNRELIABLE"
        else -> "RASTERISER_OR_PROBE_PROBLEM"
    }

    private fun countMagenta(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var count = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r > 200 && g < 60 && b > 200) count++
        }
        return count
    }

    /**
     * P2B-1A-O1b, side A. Same APK, same View/Surface/EGL, same HARNESS_LIKE host, same Haru assets and
     * viewport, same [com.aiwatch.live2d.CubismTextureManager]. Only the model setup/update/draw semantics
     * differ from `homeShowsLive2DModelSurvivesRecreationAndReleasesCleanly` (side B).
     *
     * First round asks exactly one question: does the official semantics path produce pixels here?
     */
    @Test fun o1bOfficialSemanticsProducesPixels() = captureSide("OFFICIAL", officialBackend = true)

    @Test fun o1bOurSemanticsProducesPixels() = captureSide("OURS", officialBackend = false)

    private fun captureSide(label: String, officialBackend: Boolean) {
        val home = launchHome(harnessHost = true, officialBackend = officialBackend)
        try {
            val ready = waitFor("$label runtime to report ready") {
                onMain { home.currentAvatarMode } == HomeActivity.AvatarMode.LIVE2D
            }
            println("O1B side=$label ready=$ready failure=${onMain { home.currentLive2DFailure }}")
            assertTrue("$label side: runtime did not become ready", ready)
            assertTrue("$label side: surface not laid out", waitFor("surface laid out") {
                live2dSurface(home)?.let { it.width > 0 && it.height > 0 } == true
            })
            val bitmap = captureAfterFrames(home)
            assertNotNull("$label side: could not read the surface back", bitmap)
            val (distinct, foreground) = nonBackgroundPixels(bitmap!!)
            println("O1B_PIXELS side=$label size=${bitmap.width}x${bitmap.height} " +
                "distinct=$distinct foreground=$foreground")
            bitmap.recycle()
            assertTrue("$label side is blank: distinct=$distinct", distinct > 8)
            assertTrue("$label side has no drawn content: foreground=$foreground", foreground > 2_000)
        } finally {
            instrumentation.runOnMainSync { home.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun flatten(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()

    /** Reads the presented surface back so "a model is displayed" means pixels, not a non-null object. */
    private fun captureSurface(surface: SurfaceView): Bitmap? {
        val width = surface.width
        val height = surface.height
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val result = intArrayOf(-1)
        instrumentation.runOnMainSync {
            PixelCopy.request(surface, bitmap, { code ->
                result[0] = code
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        if (!latch.await(20, TimeUnit.SECONDS) || result[0] != PixelCopy.SUCCESS) {
            println("PixelCopy failed: result=${result[0]}")
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun nonBackgroundPixels(bitmap: Bitmap): Pair<Int, Int> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val histogram = HashMap<Int, Int>()
        for (pixel in pixels) histogram[pixel] = (histogram[pixel] ?: 0) + 1
        val backgroundCount = histogram.values.maxOrNull() ?: 0
        return histogram.size to (pixels.size - backgroundCount)
    }

    /**
     * Waits for a real frame and retries the read-back. The emulator can take many seconds between
     * surface creation and the first drawn frame, and PixelCopy fails outright while the surface is still
     * settling, so a fixed sleep would be flaky in both directions.
     */
    private fun captureAfterFrames(home: HomeActivity, attempts: Int = 8): Bitmap? {
        waitFor("Live2D to draw at least one frame") { onMain { home.live2DFramesDrawn } > 0 }
        repeat(attempts) { attempt ->
            val surface = live2dSurface(home) ?: return null
            val bitmap = captureSurface(surface)
            if (bitmap != null) return bitmap
            println("capture attempt ${attempt + 1} failed; retrying")
            Thread.sleep(1_000)
        }
        return null
    }

    private fun live2dSurface(home: HomeActivity): SurfaceView? =
        flatten(home.window.decorView).filterIsInstance<SurfaceView>().firstOrNull()

    // --- criterion 5 -------------------------------------------------------------------------------

    @Test fun shaderResourcesArePackagedAndReadable() {
        val assets = instrumentation.targetContext.assets
        val names = assets.list(SHADER_DIR)?.filter { it.isNotBlank() }?.sorted().orEmpty()
        assertEquals("packaged Cubism shader count", EXPECTED_SHADERS, names.size)
        assertEquals("shader extension mix", 30, names.count { it.endsWith(".frag") })
        assertEquals("shader extension mix", 6, names.count { it.endsWith(".vert") })
        names.forEach { name ->
            val bytes = assets.open("$SHADER_DIR/$name").use { it.readBytes() }
            assertTrue("shader $name is empty", bytes.isNotEmpty())
        }
        // The shader that the runtime needs first must be present by name, not just by count.
        assertTrue(names.contains("VertShaderSrc.vert"))
        assertTrue(names.contains("FragShaderSrc.frag"))
        println("SHADER_ASSERT packaged=$EXPECTED_SHADERS readable=$EXPECTED_SHADERS")
    }

    // --- criteria 1 + 2 + 4 ------------------------------------------------------------------------

    @Test fun homeShowsLive2DModelSurvivesRecreationAndReleasesCleanly() {
        val home = launchHome(forceFailure = false)
        try {
            val becameLive2D = waitFor("Home to report Live2D ready") {
                onMain { home.currentAvatarMode } == HomeActivity.AvatarMode.LIVE2D
            }
            val failure = onMain { home.currentLive2DFailure }
            assertTrue("Home did not switch to the Live2D avatar (failure=$failure)", becameLive2D)
            assertNull("Live2D reported ready but also reported a failure", failure)

            // Static avatar is hidden while Live2D is showing, but must still be present in the tree.
            val staticAvatar = onMain {
                flatten(home.window.decorView).filterIsInstance<ImageView>().firstOrNull { it.drawable != null }
            }
            assertNotNull("static avatar ImageView disappeared", staticAvatar)
            assertEquals("static avatar should be hidden while Live2D is shown",
                View.GONE, onMain { staticAvatar!!.visibility })

            // Criterion 1: real pixels on the presented surface.
            assertTrue("Live2D surface has no size", waitFor("surface to be laid out") {
                live2dSurface(home)?.let { it.width > 0 && it.height > 0 } == true
            })
            val surface = live2dSurface(home)
            assertNotNull("no SurfaceView for the Live2D avatar", surface)
            val beforeBitmap = captureAfterFrames(home)
            assertNotNull("could not read the Live2D surface back before recreation", beforeBitmap)
            val (distinct, foreground) = nonBackgroundPixels(beforeBitmap!!)
            println("LIVE2D_PIXELS size=${beforeBitmap.width}x${beforeBitmap.height} distinct=$distinct foreground=$foreground")
            beforeBitmap.recycle()
            assertTrue("Live2D surface is blank: only $distinct distinct colours", distinct > 8)
            assertTrue("Live2D surface has no drawn content: foreground=$foreground", foreground > 2_000)

            val generationBefore = onMain { home.live2DSurfaceGenerations }

            // Criterion 4: a real EGL context loss. preserveEGLContextOnPause is false, so resume must
            // rebuild the context and re-create the runtime shaders.
            instrumentation.runOnMainSync { instrumentation.callActivityOnPause(home) }
            Thread.sleep(500)
            instrumentation.runOnMainSync { instrumentation.callActivityOnResume(home) }
            val survived = waitFor("Live2D to be ready again after surface recreation") {
                onMain { home.currentAvatarMode } == HomeActivity.AvatarMode.LIVE2D &&
                    onMain { home.live2DSurfaceGenerations } > generationBefore
            }
            println("SURFACE_RECREATION before=$generationBefore after=${onMain { home.live2DSurfaceGenerations }} " +
                "frames=${onMain { home.live2DFramesDrawn }}")
            assertTrue("Live2D did not rebuild after EGL context recreation", survived)

            val afterBitmap = captureAfterFrames(home)
            assertNotNull("surface unreadable after recreation", afterBitmap)
            val (afterDistinct, afterForeground) = nonBackgroundPixels(afterBitmap!!)
            afterBitmap.recycle()
            assertTrue("Live2D surface blank after recreation: distinct=$afterDistinct", afterDistinct > 8)
            assertTrue("Live2D surface empty after recreation: foreground=$afterForeground", afterForeground > 2_000)
        } finally {
            instrumentation.runOnMainSync { home.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    // --- criterion 3 -------------------------------------------------------------------------------

    /**
     * P2B-1A-O1a — Host Configuration Differential.
     *
     * Same APK, same [com.aiwatch.live2d.Live2DAvatarView], same EGL/Surface lifecycle, same Haru assets,
     * same [com.aiwatch.live2d.CubismRuntimeOwner] and texture manager. The only change is the host
     * configuration: platform-default EGL config / holder format / z-order and an opaque clear, which is
     * what the known-good verification harness (the PRIOR POSITIVE CONTROL) runs with.
     *
     * If this produces pixels while the PRODUCT host does not, the failure boundary is the host
     * configuration introduced during R1, and no official LAppModel oracle (O1b) is needed.
     */
    @Test fun o1aHarnessLikeHostProducesPixels() {
        val home = launchHome(harnessHost = true)
        try {
            val ready = waitFor("Live2D ready under HARNESS_LIKE host") {
                onMain { home.currentAvatarMode } == HomeActivity.AvatarMode.LIVE2D
            }
            println("O1A mode=HARNESS_LIKE ready=$ready failure=${onMain { home.currentLive2DFailure }}")
            assertTrue("Live2D did not become ready under the HARNESS_LIKE host", ready)
            assertTrue("surface not laid out", waitFor("surface laid out") {
                live2dSurface(home)?.let { it.width > 0 && it.height > 0 } == true
            })
            val bitmap = captureAfterFrames(home)
            assertNotNull("could not read the surface back under the HARNESS_LIKE host", bitmap)
            val (distinct, foreground) = nonBackgroundPixels(bitmap!!)
            println("O1A_PIXELS mode=HARNESS_LIKE size=${bitmap.width}x${bitmap.height} " +
                "distinct=$distinct foreground=$foreground")
            bitmap.recycle()
            assertTrue("HARNESS_LIKE host is still blank: distinct=$distinct", distinct > 8)
            assertTrue("HARNESS_LIKE host has no drawn content: foreground=$foreground", foreground > 2_000)
        } finally {
            instrumentation.runOnMainSync { home.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun live2DFailureFallsBackToStaticAvatar() {
        val home = launchHome(forceFailure = true)
        try {
            val fellBack = waitFor("Home to fall back to the static avatar") {
                onMain { home.currentAvatarMode } == HomeActivity.AvatarMode.STATIC &&
                    onMain { home.currentLive2DFailure } != null
            }
            println("FALLBACK reason=${onMain { home.currentLive2DFailure }}")
            assertTrue("Home did not fall back when Live2D failed to initialise", fellBack)

            // No Live2D surface should remain once we have fallen back.
            assertEquals("Live2D surface left behind after fallback", 0, onMain { home.live2DSurfaceGenerations })
            assertNull("Live2D surface left behind after fallback", live2dSurface(home))

            val visibleAvatar = onMain {
                flatten(home.window.decorView).filterIsInstance<ImageView>()
                    .firstOrNull { it.drawable != null && it.visibility == View.VISIBLE }
            }
            assertNotNull("static avatar is not visible after fallback", visibleAvatar)

            // Home must still be usable: the talk control is present and the shell did not crash.
            val talk = onMain {
                flatten(home.window.decorView).filterIsInstance<Button>()
                    .firstOrNull { it.text.toString() == home.getString(R.string.product_talk) }
            }
            assertNotNull("Home lost its talk control after fallback", talk)
            assertFalse("Home came up already connected", onMain { talk!!.isEnabled })
        } finally {
            instrumentation.runOnMainSync { home.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
