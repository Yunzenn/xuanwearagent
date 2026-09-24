package com.aiwatch.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** Credentials intentionally omitted from diagnostic string representations. */
class WebSocketConfig(val url: String, val token: String?) {
    override fun toString() = "WebSocketConfig(redacted)"
}

class ActivationInfo(
    val code: String?, val message: String?, val challenge: String?, val timeoutMs: Int,
) {
    override fun toString() = "ActivationInfo(redacted)"
}

sealed interface BootstrapResult {
    data class Ready(val websocket: WebSocketConfig) : BootstrapResult
    data class ActivationRequired(val activation: ActivationInfo) : BootstrapResult
    data class Invalid(val reason: String) : BootstrapResult
    data class Failure(val kind: Kind, val httpStatus: Int? = null) : BootstrapResult {
        enum class Kind { HTTP, NETWORK }
    }
}

/** Field semantics adapted from frozen rokid-xiaozhi fetchConfig; no HTTP side effects. */
class BootstrapResponseParser(private val allowInsecureDevelopment: Boolean = false) {
    fun parse(body: String): BootstrapResult = try {
        require(body.length <= 65_536)
        val root = JsonParser.parseString(body).asJsonObject
        // Activation always takes precedence over credentials returned alongside it.
        if (root.has("activation")) {
            val activation = root.getAsJsonObject("activation")
            val timeout = activation.get("timeout_ms")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
                it.asBigDecimal.intValueExact().also { value -> require(value > 0) }
            } ?: 30_000
            BootstrapResult.ActivationRequired(ActivationInfo(
                activation.optional("code"), activation.optional("message"),
                activation.optional("challenge"), timeout,
            ))
        } else {
            val websocket = root.getAsJsonObject("websocket")
            val url = requireNotNull(websocket.optional("url"))
            val uri = URI(url)
            require(uri.scheme == "wss" || (allowInsecureDevelopment && uri.scheme == "ws"))
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
            BootstrapResult.Ready(WebSocketConfig(url, websocket.optional("token")))
        }
    } catch (_: Exception) {
        BootstrapResult.Invalid("Invalid or unsupported bootstrap response")
    }

    private fun JsonObject.optional(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
}
