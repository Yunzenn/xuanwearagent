package com.aiwatch.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser

interface XiaozhiProtocol {
    fun parse(text: String): ProtocolEvent
    fun clientHello(): String
    fun listen(sessionId: String, start: Boolean): String
    fun abort(sessionId: String): String
}

/** v1 only. No MCP/AEC capability is advertised. Invalid playback params fail closed. */
class XiaozhiProtocolV1 : XiaozhiProtocol {
    override fun parse(text: String): ProtocolEvent = try {
        require(text.length <= 65_536) { "Frame too large" }
        val json = JsonParser.parseString(text).asJsonObject
        when (val type = json.string("type")) {
            "hello" -> {
                require(json.string("transport") == "websocket")
                val audio = json.getAsJsonObject("audio_params")
                val config = PlaybackAudioConfig(
                    audio.string("format"), audio.integer("sample_rate"),
                    audio.integer("channels"), audio.integer("frame_duration"),
                )
                require(config.format == "opus")
                require(config.sampleRate in setOf(8000, 12000, 16000, 24000, 48000))
                require(config.channels in 1..2)
                require(config.frameDurationMs in setOf(10, 20, 40, 60))
                ProtocolEvent.Hello(json.optionalString("session_id") ?: "", config)
            }
            "stt" -> ProtocolEvent.Stt(json.string("text"))
            "llm" -> ProtocolEvent.Llm(json.optionalString("text") ?: "", json.optionalString("emotion"))
            "tts" -> ProtocolEvent.Tts(
                when (json.string("state")) {
                    "start" -> ProtocolEvent.Tts.Phase.START
                    "sentence_start" -> ProtocolEvent.Tts.Phase.SENTENCE_START
                    "stop" -> ProtocolEvent.Tts.Phase.STOP
                    else -> error("Unsupported TTS state")
                }, json.optionalString("text"),
            )
            "mcp" -> ProtocolEvent.Mcp(requireNotNull(json.get("payload")).toString())
            "error" -> ProtocolEvent.Error(json.optionalString("message") ?: "Server error")
            else -> ProtocolEvent.Unknown(type)
        }
    } catch (_: Exception) {
        // Do not put remote content or credentials in error messages.
        ProtocolEvent.Error("Invalid protocol message")
    }

    override fun clientHello(): String = JsonObject().apply {
        addProperty("type", "hello")
        addProperty("version", 1)
        addProperty("transport", "websocket")
        add("audio_params", JsonObject().apply {
            addProperty("format", "opus")
            addProperty("sample_rate", 16000)
            addProperty("channels", 1)
            addProperty("frame_duration", 60)
        })
    }.toString()

    override fun listen(sessionId: String, start: Boolean): String = JsonObject().apply {
        addProperty("session_id", sessionId)
        addProperty("type", "listen")
        addProperty("state", if (start) "start" else "stop")
        if (start) addProperty("mode", "manual")
    }.toString()

    override fun abort(sessionId: String): String = JsonObject().apply {
        addProperty("session_id", sessionId)
        addProperty("type", "abort")
    }.toString()

    private fun JsonObject.string(key: String): String {
        val value = requireNotNull(get(key))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun JsonObject.optionalString(key: String): String? =
        if (!has(key)) null else string(key)

    private fun JsonObject.integer(key: String): Int {
        val value = requireNotNull(get(key))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.intValueExact()
    }
}
