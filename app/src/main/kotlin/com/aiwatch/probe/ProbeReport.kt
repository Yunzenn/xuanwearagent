package com.aiwatch.probe

import android.app.ActivityManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Environment

data class ProbeReport(val sections: Map<String, Map<String, String>>) {
    fun asMarkdown(): String = buildString {
        appendLine("# Device Capability Report")
        sections.forEach { (section, values) ->
            appendLine(); appendLine("## $section")
            values.forEach { (key, value) -> appendLine("- $key: $value") }
        }
    }

    fun asJson(): String = sections.entries.joinToString(",", "{", "}") { (section, values) ->
        "\"${section.jsonEscape()}\":" + values.entries.joinToString(",", "{", "}") { (key, value) ->
            "\"${key.jsonEscape()}\":\"${value.jsonEscape()}\""
        }
    }

    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    companion object {
        fun collect(context: Context): ProbeReport {
            val metrics = context.resources.displayMetrics
            val manager = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val inputMin = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val outputMin = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return ProbeReport(linkedMapOf(
                "Device" to linkedMapOf(
                    "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                    "androidRelease" to Build.VERSION.RELEASE, "api" to Build.VERSION.SDK_INT.toString(),
                    "abis" to Build.SUPPORTED_ABIS.joinToString(), "hardware" to Build.HARDWARE,
                    "ramBytes" to memory.totalMem.toString()),
                "Graphics" to linkedMapOf(
                    "glEsVersion" to manager.deviceConfigurationInfo.glEsVersion,
                    "widthPixels" to metrics.widthPixels.toString(), "heightPixels" to metrics.heightPixels.toString(),
                    "densityDpi" to metrics.densityDpi.toString()),
                "Audio" to linkedMapOf(
                    "pcm16Mono16kInputMinBuffer" to inputMin.toString(),
                    "pcm16Mono16kOutputMinBuffer" to outputMin.toString(),
                    "direct16kInputSupported" to (inputMin > 0).toString(),
                    "direct16kOutputSupported" to (outputMin > 0).toString(),
                    "status" to "skeleton-only; capture/playback/Opus loopback not executed"),
                "Storage" to linkedMapOf(
                    "filesDir" to context.filesDir.absolutePath,
                    "externalStorageState" to Environment.getExternalStorageState(),
                    "safStatus" to "requires interactive ACTION_OPEN_DOCUMENT test")
            ))
        }
    }
}
