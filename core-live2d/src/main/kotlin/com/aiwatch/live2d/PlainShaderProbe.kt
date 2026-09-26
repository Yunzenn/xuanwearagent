package com.aiwatch.live2d

import android.opengl.GLES20
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * P2B-1A infrastructure check. Debug-only; never part of the product draw path.
 *
 * It answers the one question the Cubism path cannot answer about itself: can this GL context rasterise
 * anything at all? The program is deliberately trivial — positions passed straight through, a constant
 * magenta fragment — so nothing about Live2D, the model, the projection, the index buffer or the texture
 * pipeline can influence the result.
 *
 * Why it exists: every black-screen conclusion so far rested on `glReadPixels` alone, and the "known-good"
 * harness control turned out to be satisfiable by the Sample's background/UI sprites rather than by the
 * Cubism model. This probe is read back twice in the same run — `glReadPixels` inside the frame and
 * `PixelCopy` on the presented surface — so the two instruments calibrate each other.
 *
 * The probe intentionally does NOT clear: leaving the frame intact lets the same run also report whatever
 * the model path produced. Magenta is nowhere in the Live2D output, so its presence is unambiguous.
 */
internal class PlainShaderProbe {

    private var program = 0
    private var positionLocation = -1
    private var prepared = false
    private var capabilitiesLogged = false

    /** Human-readable outcome of the most recent [draw], for the instrumentation test to read. */
    @Volatile
    var lastResult: String? = null
        private set

    private val triangle: FloatBuffer =
        ByteBuffer.allocateDirect(VERTEX_COUNT * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(-0.5f)
            put(-0.5f)
            put(0.5f)
            put(-0.5f)
            put(0.0f)
            put(0.5f)
            position(0)
        }

    /** Draws the triangle into framebuffer 0 and reports what the GL-thread readback observed. */
    fun draw(width: Int, height: Int): String {
        require(width > 0 && height > 0) { "probe needs a real surface size, got ${width}x$height" }

        prepare()?.let { reason ->
            return "PLAINSHADER outcome=FAIL stage=prepare reason=$reason".also { lastResult = it }
        }

        // Device capability read (B1/B2). Independent of Live2D: the two values that decide whether an
        // 8192x8192 atlas can exist on this GPU at all, and whether our ABI set matches the ROM.
        // Read from the same context the product uses, because a ROM's dumpsys values are not reliable.
        if (!capabilitiesLogged) {
            capabilitiesLogged = true
            val maxTexture = IntArray(1)
            val maxTextureUnits = IntArray(1)
            val maxRenderbuffer = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, maxTextureUnits, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, maxRenderbuffer, 0)
            Log.i(
                TAG,
                "GLES_CAPABILITY maxTextureSize=${maxTexture[0]} " +
                    "maxTextureImageUnits=${maxTextureUnits[0]} " +
                    "maxRenderbufferSize=${maxRenderbuffer[0]} " +
                    "abis=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                    "abis64=${Build.SUPPORTED_64_BIT_ABIS.joinToString(",")} " +
                    "supports8192Atlas=${maxTexture[0] >= 8192} " +
                    "supportsArm64=${Build.SUPPORTED_ABIS.contains("arm64-v8a")}",
            )
        }

        // Set every state bit that could suppress a fragment, explicitly, rather than trusting the caller.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            FLOAT_BYTES * 2,
            triangle,
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, VERTEX_COUNT)
        val drawError = GLES20.glGetError()

        val readback = readBack(width, height)

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glUseProgram(0)

        val outcome = if (readback.magentaPixels > 0) "PASS" else "FAIL"
        val result = "PLAINSHADER outcome=$outcome program=$program aPosLocation=$positionLocation " +
            "viewport=${width}x$height drawGlError=0x${Integer.toHexString(drawError)} " +
            "readGlError=0x${Integer.toHexString(readback.glError)} " +
            "magentaPixels=${readback.magentaPixels} firstMagentaIndex=${readback.firstMagentaIndex} " +
            "maxRed=${readback.maxRed} maxGreen=${readback.maxGreen} maxBlue=${readback.maxBlue} " +
            "distinctColors=${readback.distinctColors}"
        // Logged only when it changes: the probe runs every frame and would otherwise flood the buffer.
        if (result != lastResult) {
            Log.i(TAG, result)
        }
        lastResult = result
        return result
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        prepared = false
    }

    private fun prepare(): String? {
        if (prepared) {
            return null
        }
        prepared = true

        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE)
            ?: return "vertex shader did not compile"
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE)
            ?: return "fragment shader did not compile"

        program = GLES20.glCreateProgram()
        if (program == 0) {
            return "glCreateProgram returned 0"
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != 1) {
            return "program did not link: ${GLES20.glGetProgramInfoLog(program)}"
        }
        positionLocation = GLES20.glGetAttribLocation(program, "aPos")
        return if (positionLocation < 0) "attribute aPos has no location" else null
    }

    private fun compile(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            return null
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != 1) {
            Log.i(TAG, "shader compile log: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private data class Readback(
        val magentaPixels: Int,
        val firstMagentaIndex: Int,
        val maxRed: Int,
        val maxGreen: Int,
        val maxBlue: Int,
        val distinctColors: Int,
        val glError: Int,
    )

    private fun readBack(width: Int, height: Int): Readback {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // drain sticky errors so the read below is attributed to the read
        }
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val error = GLES20.glGetError()

        var magenta = 0
        var firstMagenta = -1
        var maxRed = 0
        var maxGreen = 0
        var maxBlue = 0
        val distinct = HashSet<Int>()

        for (i in 0 until width * height) {
            val r = buffer.get(i * 4).toInt() and 0xFF
            val g = buffer.get(i * 4 + 1).toInt() and 0xFF
            val b = buffer.get(i * 4 + 2).toInt() and 0xFF
            val a = buffer.get(i * 4 + 3).toInt() and 0xFF
            if (r > maxRed) maxRed = r
            if (g > maxGreen) maxGreen = g
            if (b > maxBlue) maxBlue = b
            if (r > 200 && g < 60 && b > 200) {
                magenta++
                if (firstMagenta < 0) firstMagenta = i
            }
            if (distinct.size < 4096) {
                distinct.add((r shl 24) or (g shl 16) or (b shl 8) or a)
            }
        }

        return Readback(magenta, firstMagenta, maxRed, maxGreen, maxBlue, distinct.size, error)
    }

    private companion object {
        const val TAG = "PlainShader"
        const val VERTEX_COUNT = 3
        const val FLOAT_BYTES = 4

        const val VERTEX_SHADER_SOURCE = """
            attribute vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER_SOURCE = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(1.0, 0.0, 1.0, 1.0);
            }
        """
    }
}
