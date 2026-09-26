package com.aiwatch.probe.product

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URI

data class CharacterProfile(val name: String = "小星")
data class ServerProfile(val endpoint: String = "")

/** Local presentation settings, separate from device identity and server-owned voice/personality. */
class ProductStore(private val directory: File) {
    init { check(directory.exists() || directory.mkdirs()) }
    private fun read(name: String): JSONObject {
        val file = AtomicFile(File(directory, name))
        return try { JSONObject(file.openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { JSONObject() }
    }
    private fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    @Synchronized fun character() = CharacterProfile(read("character.json").optString("name", "小星"))
    @Synchronized fun server() = ServerProfile(read("server.json").optString("endpoint", ""))
    @Synchronized fun saveCharacter(name: String) {
        val value = name.trim()
        require(value.isNotEmpty() && value.length <= 32 && value.none { it.isISOControl() })
        write("character.json", JSONObject().put("name", value))
    }
    @Synchronized fun saveServer(endpoint: String) {
        val value = endpoint.trim()
        require(validEndpoint(value))
        write("server.json", JSONObject().put("endpoint", value))
    }
    @Synchronized fun avatar(): Bitmap? {
        val file = AtomicFile(File(directory, "avatar.png"))
        return try { file.openRead().use { android.graphics.BitmapFactory.decodeStream(it) } }
        catch (_: java.io.FileNotFoundException) { null }
    }

    /** Bounded read, header validation, normalized private copy; no persistent URI dependency. */
    @Synchronized fun importImage(input: InputStream) {
        val temporary = File.createTempFile("image-", ".input", directory)
        try {
            input.use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Image exceeds byte limit" }
                    output.write(buffer, 0, count)
                }
            } }
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(temporary)) { decoder, info, _ ->
                require(info.mimeType in setOf("image/png", "image/jpeg", "image/webp") && !info.isAnimated)
                val width = info.size.width
                val height = info.size.height
                require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32_000_000)
                val scale = minOf(1.0, 1024.0 / maxOf(width, height))
                decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                val destination = AtomicFile(File(directory, "avatar.png"))
                val output = destination.startWrite()
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    destination.finishWrite(output)
                } catch (failure: Exception) { destination.failWrite(output); throw failure }
            } finally { bitmap.recycle() }
        } finally { temporary.delete() }
    }
    @Synchronized fun resetAvatar() { AtomicFile(File(directory, "avatar.png")).delete() }

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        fun validEndpoint(value: String): Boolean {
            if (value.isEmpty()) return true
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return value.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null && uri.query == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }
    }
}
