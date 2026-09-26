package com.aiwatch.live2d

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Minimal test host for the Live2D regression suite.
 *
 * Deliberately NOT the deleted `product.HomeActivity`. It carries only what the regression tests need to
 * drive [Live2DAvatarView] and observe the result, and it depends on nothing from `:app`:
 *
 *   - no ProductUi, ProductStore or SettingsActivity
 *   - no audio session, no push-to-talk, no character configuration
 *   - no `ProbeApplication` cast: the runtime only ever needed an Application context, so a plain
 *     `Application` is sufficient. That is what makes this host survive outside the product APK.
 *
 * The public surface below is exactly the set the migrated tests use; it exists so those tests did not
 * have to be rewritten, not because the product needs it.
 */
class Live2DRegressionActivity : Activity() {

    enum class AvatarMode { STATIC, LIVE2D }

    private lateinit var container: FrameLayout
    private lateinit var staticAvatar: ImageView
    private var live2dView: Live2DAvatarView? = null

    var avatarMode: AvatarMode = AvatarMode.STATIC
        private set
    var live2dFailure: String? = null
        private set

    val currentAvatarMode: AvatarMode get() = avatarMode
    val currentLive2DFailure: String? get() = live2dFailure
    val live2DSurfaceGenerations: Int get() = live2dView?.surfaceGenerations ?: 0
    val live2DFramesDrawn: Long get() = live2dView?.framesDrawn ?: 0L
    val live2DPlainShaderProbeResult: String? get() = live2dView?.plainShaderProbeResult

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        staticAvatar = ImageView(this).apply {
            setBackgroundColor(Color.DKGRAY)
            visibility = View.VISIBLE
        }
        container.addView(staticAvatar, FrameLayout.LayoutParams(-1, -1))
        setContentView(container)

        val hostMode = if (intent.getBooleanExtra(EXTRA_HOST_MODE_HARNESS, false)) {
            Live2DAvatarView.HostMode.HARNESS_LIKE
        } else {
            Live2DAvatarView.HostMode.PRODUCT
        }
        val backend = if (intent.getBooleanExtra(EXTRA_RUNTIME_BACKEND_OFFICIAL, false)) {
            Live2DAvatarView.RuntimeBackend.OFFICIAL
        } else {
            Live2DAvatarView.RuntimeBackend.OURS
        }

        val view = Live2DAvatarView(
            this,
            null,
            hostMode,
            backend,
            intent.getBooleanExtra(EXTRA_PLAIN_SHADER_PROBE, false),
        )
        if (intent.getBooleanExtra(EXTRA_FORCE_LIVE2D_FAILURE, false)) {
            view.modelDirectory = "does-not-exist/"
            view.modelJson = "missing.model3.json"
        }
        view.listener = object : Live2DAvatarView.Listener {
            override fun onLive2DReady(surfaceGeneration: Int) = runOnUiThread { showLive2D() }

            override fun onLive2DFailed(reason: String) = runOnUiThread { fallbackToStatic(reason) }
        }
        live2dView = view
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showLive2D() {
        avatarMode = AvatarMode.LIVE2D
        staticAvatar.visibility = View.GONE
    }

    private fun fallbackToStatic(reason: String) {
        avatarMode = AvatarMode.STATIC
        live2dFailure = reason
        staticAvatar.visibility = View.VISIBLE
        live2dView?.let { container.removeView(it) }
        live2dView = null
    }

    override fun onPause() {
        super.onPause()
        live2dView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        live2dView?.onResume()
    }

    override fun onDestroy() {
        live2dView?.releaseRuntime()
        live2dView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FORCE_LIVE2D_FAILURE = "com.aiwatch.live2d.FORCE_LIVE2D_FAILURE"
        const val EXTRA_HOST_MODE_HARNESS = "com.aiwatch.live2d.HOST_MODE_HARNESS"
        const val EXTRA_RUNTIME_BACKEND_OFFICIAL = "com.aiwatch.live2d.RUNTIME_BACKEND_OFFICIAL"
        const val EXTRA_PLAIN_SHADER_PROBE = "com.aiwatch.live2d.PLAIN_SHADER_PROBE"
    }
}
