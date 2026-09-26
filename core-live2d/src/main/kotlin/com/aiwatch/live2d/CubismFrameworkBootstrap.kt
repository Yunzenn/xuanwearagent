package com.aiwatch.live2d

import android.content.Context
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction

/**
 * Process-wide one-time Cubism framework start-up.
 *
 * This is global because the framework itself is global: `CubismFramework.startUp` accepts the
 * file-loading function exactly once per process. It deliberately captures only an APPLICATION context
 * — the load function needs an [android.content.res.AssetManager] and nothing else — so no Activity can
 * ever be retained here. Per-view lifetime belongs to [CubismRuntimeOwner], which the owning View creates
 * and releases explicitly.
 */
internal object CubismFrameworkBootstrap {
    @Volatile private var started = false

    @Synchronized
    fun ensureStarted(context: Context) {
        if (started && CubismFramework.isStarted()) return
        val assets = context.applicationContext.assets
        val option = CubismFramework.Option()
        option.loadFileFunction = ICubismLoadFileFunction { path ->
            // Logged on purpose: the framework loads its GLSL shaders through this hook, and a wrong path
            // makes it render nothing while still reporting no GL error.
            Log.i(CubismRuntimeOwner.TAG, "loadFile: $path")
            CubismAssets.readRequired(assets, path)
        }
        CubismFramework.cleanUp()
        CubismFramework.startUp(option)
        if (!CubismFramework.isInitialized()) CubismFramework.initialize()
        started = true
        Log.i(CubismRuntimeOwner.TAG, "Cubism framework started (application context only)")
    }
}
