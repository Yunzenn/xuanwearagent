package com.aiwatch.live2d

/**
 * Contract shared by the product runtime and the P2B-1A-O1b oracle runtime.
 *
 * It exists so both can be driven by the exact same `Live2DAvatarView`, Surface and EGL context, which is
 * the whole point of O1b: the only thing that may differ between A and B is the model
 * setup / layout / update / draw semantics.
 */
interface AvatarRuntime {
    val isReady: Boolean
    val failureMessage: String?
    val framesDrawn: Long

    /** True when the renderer is in the official premultiplied-alpha shader variant. */
    val premultipliedAlpha: Boolean

    fun initialize(modelDirectory: String, modelJson: String): Boolean
    fun resize(width: Int, height: Int)
    fun update()
    fun draw()
    fun release()
}
