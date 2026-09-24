package com.aiwatch.protocol

import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Bounded single request. Redirects and automatic retries are disabled for identity-bearing POSTs. */
class BootstrapRepository(
    client: OkHttpClient = OkHttpClient(),
    private val allowInsecureDevelopment: Boolean = false,
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build()
    private val parser = BootstrapResponseParser(allowInsecureDevelopment)

    suspend fun fetch(endpoint: String, identity: DeviceIdentity, appVersion: String): BootstrapResult {
        val request = try {
            val url = endpoint.toHttpUrl()
            require(url.isHttps || allowInsecureDevelopment)
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            require(appVersion.isNotBlank())
            val body = JsonObject().apply {
                addProperty("version", 2)
                addProperty("mac_address", identity.deviceId)
                addProperty("uuid", identity.clientId)
                add("application", JsonObject().apply {
                    addProperty("name", "xuanwearagent")
                    addProperty("version", appVersion)
                })
                add("board", JsonObject().apply { addProperty("type", "android") })
            }
            Request.Builder().url(url).header("Device-Id", identity.deviceId)
                .header("Client-Id", identity.clientId).header("Activation-Version", "1")
                .header("User-Agent", "xuanwearagent/$appVersion")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        } catch (_: IllegalArgumentException) {
            return BootstrapResult.Invalid("Invalid bootstrap request configuration")
        }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(BootstrapResult.Failure(BootstrapResult.Failure.Kind.NETWORK))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!it.isSuccessful) BootstrapResult.Failure(BootstrapResult.Failure.Kind.HTTP, it.code)
                            else {
                                val source = it.body?.source()
                                if (source == null) BootstrapResult.Invalid("Missing bootstrap body")
                                else {
                                    val buffer = okio.Buffer()
                                    while (buffer.size <= 65_536) {
                                        if (source.read(buffer, 65_537 - buffer.size) == -1L) break
                                    }
                                    if (buffer.size > 65_536) BootstrapResult.Invalid("Bootstrap body too large")
                                    else parser.parse(buffer.readUtf8())
                                }
                            }
                        }
                    } catch (_: IOException) {
                        BootstrapResult.Failure(BootstrapResult.Failure.Kind.NETWORK)
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}
