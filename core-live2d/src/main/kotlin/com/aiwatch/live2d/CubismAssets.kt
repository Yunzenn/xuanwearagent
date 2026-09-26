package com.aiwatch.live2d

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.IOException

/**
 * Asset access for the Cubism runtime.
 *
 * Everything here takes an [AssetManager] obtained from an APPLICATION context. The official sample
 * instead keeps a static Activity in `LAppPal` and loads files through it; that lifetime coupling is
 * exactly what this module refuses to reproduce, because it breaks whenever the Activity is recreated.
 */
internal object CubismAssets {
    /** Asset directory holding the runtime-loaded GLSL shaders. */
    const val SHADER_DIR = "com/live2d/sdk/cubism/framework/shaders/standardES"

    /** The official framework ships 30 .frag + 6 .vert files. */
    const val EXPECTED_SHADER_COUNT = 36

    fun readRequired(assets: AssetManager, path: String): ByteArray {
        val normalized = path.removePrefix("/")
        return try {
            assets.open(normalized).use { it.readBytes() }
        } catch (e: IOException) {
            throw IllegalStateException("Cubism asset missing: '$path' (tried '$normalized')", e)
        }
    }

    fun readBitmap(assets: AssetManager, path: String): Bitmap? {
        val normalized = path.removePrefix("/")
        return try {
            assets.open(normalized).use { BitmapFactory.decodeStream(it) }
        } catch (e: IOException) {
            Log.e(CubismRuntimeOwner.TAG, "texture missing: $path", e)
            null
        }
    }

    /** Names of the packaged shader files; used by the runtime layer of the shader assertion. */
    fun shaderFiles(assets: AssetManager): List<String> =
        assets.list(SHADER_DIR)?.filter { it.isNotBlank() }?.sorted().orEmpty()
}
