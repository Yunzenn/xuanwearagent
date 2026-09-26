package com.aiwatch.live2d.oracle

import android.content.Context
import android.util.Log
import com.aiwatch.live2d.AvatarRuntime
import com.aiwatch.live2d.CubismAssets
import com.aiwatch.live2d.CubismFrameworkBootstrap
import com.aiwatch.live2d.CubismRuntimeOwner
import com.aiwatch.live2d.CubismTextureManager
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.math.CubismModelMatrix
import com.live2d.sdk.cubism.framework.model.CubismModel
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.CubismMotionManager
import com.live2d.sdk.cubism.framework.motion.CubismUpdateScheduler
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid

/**
 * P2B-1A-O1b, side A: the official Sample's model semantics.
 *
 * This is a DEBUG/DIAGNOSTIC-ONLY runtime. It is never selected by product code unless the debug intent
 * extra asks for it, and it deliberately does NOT bring in the Sample App architecture — no
 * `LAppDelegate` singleton, no `LAppLive2DManager`, no Activity global, no scene manager, no Sample UI.
 *
 * What it does reuse from the official Sample is the semantics that are under test:
 *   * model creation through the framework's own `CubismUserModel.loadModel()`
 *   * renderer setup through `CubismUserModel.setupRenderer()`
 *   * the official update ordering: loadParameters -> (motion) -> saveParameters ->
 *     updateScheduler.onLateUpdate -> model.update()
 *   * the official draw semantics: modelMatrix x projection, setMvpMatrix, drawModel
 *
 * It shares the already-proven [CubismTextureManager] with side B on purpose: texture binding has already
 * been established as correct (`boundTextures` complete, `glIsTexture` true, no missing indices), so
 * giving each side its own texture path would reintroduce a variable that has been excluded.
 *
 * Known simplification, recorded rather than hidden: no motion is started, so the `updateScheduler` has no
 * registered updaters and `onLateUpdate` is a no-op here. Motion-driven parameter updates are therefore
 * NOT part of this first oracle round; the render path is.
 */
internal class OfficialOracleRuntime(private val appContext: Context) : AvatarRuntime {

    override var failureMessage: String? = null
        private set
    override var framesDrawn: Long = 0L
        private set
    override val isReady: Boolean get() = ready
    override val premultipliedAlpha: Boolean
        get() = runCatching { userModel.getRenderer<CubismRendererAndroid>().isPremultipliedAlpha }.getOrDefault(false)

    private var ready = false
    private val userModel = OracleUserModel()
    private val textures = CubismTextureManager()
    private val projection = CubismMatrix44.create()
    private var modelMatrix: CubismModelMatrix? = null
    private var rendererWidth = 0
    private var rendererHeight = 0
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastFrameNanos = 0L

    override fun initialize(modelDirectory: String, modelJson: String): Boolean {
        release()
        return try {
            val assets = appContext.assets
            CubismFrameworkBootstrap.ensureStarted(appContext)
            Log.i(
                TAG,
                "oracleA framework started=${CubismFramework.isStarted()} " +
                    "initialized=${CubismFramework.isInitialized()}",
            )
            val setting = CubismModelSettingJson(CubismAssets.readRequired(assets, modelDirectory + modelJson))
            userModel.load(CubismAssets.readRequired(assets, modelDirectory + setting.modelFileName))
            val model = userModel.cubismModel
                ?: throw IllegalStateException("CubismUserModel.loadModel produced no model")
            textures.load(assets, modelDirectory, setting)
            modelMatrix = CubismModelMatrix.create(model.canvasWidth, model.canvasHeight)
            ready = true
            failureMessage = null
            Log.i(TAG, "oracleA ready: $modelDirectory$modelJson textures=${textures.textureCount}")
            true
        } catch (t: Throwable) {
            failureMessage = t.message ?: t::class.java.simpleName
            Log.e(TAG, "oracleA initialisation failed", t)
            release()
            false
        }
    }

    override fun resize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    override fun update() {
        val model = userModel.cubismModel ?: return
        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) 0.0f else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
        lastFrameNanos = now
        // Official ordering (motion step omitted, see class note).
        model.loadParameters()
        model.saveParameters()
        userModel.scheduler.onLateUpdate(model, delta)
        model.update()
    }

    override fun draw() {
        val model = userModel.cubismModel ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val renderer = ensureRenderer(viewWidth, viewHeight)

        val aspectRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val displayRatio = viewHeight.toFloat() / viewWidth.toFloat()
        val canvasRatio = model.canvasHeight / model.canvasWidth

        projection.loadIdentity()
        val matrix = modelMatrix
        if (canvasRatio < displayRatio) {
            matrix?.setWidth(2.0f)
            projection.scale(1.0f, aspectRatio)
        } else {
            matrix?.setHeight(2.0f)
            projection.scale(1.0f / aspectRatio, 1.0f)
        }
        if (matrix != null) {
            CubismMatrix44.multiply(matrix.array, projection.array, projection.array)
        }
        renderer.setMvpMatrix(projection)
        renderer.setRenderTargetSize(viewWidth, viewHeight)
        if (framesDrawn == 0L) {
            Log.i(
                TAG,
                "oracleA diag premultipliedAlpha=${renderer.isPremultipliedAlpha} " +
                    "frameworkStarted=${CubismFramework.isStarted()} " +
                    "frameworkInitialized=${CubismFramework.isInitialized()} " +
                    textures.describeBinding(renderer, textures.textureCount),
            )
        }
        renderer.drawModel()
        framesDrawn++
    }

    override fun release() {
        runCatching { userModel.deleteRenderer() }
        textures.release()
        modelMatrix = null
        rendererWidth = 0
        rendererHeight = 0
        framesDrawn = 0L
        lastFrameNanos = 0L
        ready = false
    }

    /** Mirrors the Sample: the renderer is built by the framework helper with the real surface size. */
    private fun ensureRenderer(width: Int, height: Int): CubismRendererAndroid {
        val existing = runCatching { userModel.getRenderer<CubismRendererAndroid>() }.getOrNull()
        if (existing != null && rendererWidth == width && rendererHeight == height) return existing

        // Official path: CubismUserModel.setupRenderer() deletes any previous renderer and initialises the
        // new one against the same model.
        userModel.setupRenderer(CubismRendererAndroid.create(width, height))
        val created = userModel.getRenderer<CubismRendererAndroid>()
        created.isPremultipliedAlpha(true)
        textures.bindAll(created)
        rendererWidth = width
        rendererHeight = height
        Log.i(TAG, "oracleA renderer ${width}x$height " + textures.describeBinding(created, textures.textureCount))
        return created
    }

    /** Thin subclass purely to reach the framework's protected official entry points. */
    private class OracleUserModel : CubismUserModel() {
        val cubismModel: CubismModel? get() = model
        val scheduler: CubismUpdateScheduler get() = updateScheduler
        @Suppress("unused")
        val motions: CubismMotionManager get() = motionManager
        fun load(bytes: ByteArray) = loadModel(bytes)
    }

    private companion object {
        const val TAG = CubismRuntimeOwner.TAG
    }
}
