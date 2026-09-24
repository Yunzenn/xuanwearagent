package com.aiwatch.protocol

import java.io.Closeable
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

enum class DisconnectKind { NETWORK, AUTH, PROTOCOL, LOCAL, NORMAL, TLS, OVERLOAD }

interface SessionTransport : Closeable {
    suspend fun nextEvent(): SocketEvent
    fun connect(config: WebSocketConfig, identity: DeviceIdentity): Long
    fun listen(start: Boolean): Boolean
    fun abort(): Boolean
    fun disconnect()
}

sealed interface SocketEvent {
    val connectionId: Long
    data class Message(override val connectionId: Long, val event: ProtocolEvent) : SocketEvent
    data class Disconnected(override val connectionId: Long, val reason: String,
        val kind: DisconnectKind = DisconnectKind.NETWORK) : SocketEvent
}

/** Thread-safe v1 transport. One event consumer; each queued event retains its connection ID.
 * Reconnect is explicit: disconnect then connect. Never replay audio across connections.
 */
class WebSocketTransport(
    client: OkHttpClient = OkHttpClient(),
    private val allowInsecureDevelopment: Boolean = false,
    private val helloTimeoutMs: Long = 10_000,
) : SessionTransport {
    init { require(helloTimeoutMs > 0) }
    private val lock = Any()
    private val machine = TransportStateMachine()
    private val protocol = XiaozhiProtocolV1()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "xiaozhi-handshake-timeout").apply { isDaemon = true }
    }
    private val events = Channel<SocketEvent>(64)
    private var socket: WebSocket? = null
    private var deadline: ScheduledFuture<*>? = null
    private var closed = false
    val state: TransportState get() = synchronized(lock) { machine.state }
    val connectionId: Long get() = synchronized(lock) { machine.connectionId }
    val playbackConfig: PlaybackAudioConfig? get() = synchronized(lock) { machine.hello?.audio }

    override suspend fun nextEvent(): SocketEvent = events.receive()

    override fun connect(config: WebSocketConfig, identity: DeviceIdentity): Long = synchronized(lock) {
        check(!closed && machine.state == TransportState.DISCONNECTED)
        val uri = URI(config.url)
        require(uri.scheme == "wss" || (allowInsecureDevelopment && uri.scheme == "ws"))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        val request = Request.Builder().url(config.url)
            .header("Protocol-Version", "1")
            .header("Device-Id", identity.deviceId).header("Client-Id", identity.clientId)
            .apply {
                config.token?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", if (it.startsWith("Bearer ")) it else "Bearer $it")
                }
            }.build()
        val id = machine.connect()
        deadline = timer.schedule({ synchronized(lock) {
            if (active(id) && machine.state != TransportState.READY) finish(id, "Handshake timeout")
        } }, helloTimeoutMs, TimeUnit.MILLISECONDS)
        socket = http.newWebSocket(request, listener(id))
        id
    }

    override fun listen(start: Boolean): Boolean = synchronized(lock) {
        sendText(protocol.listen(machine.hello?.sessionId ?: "", start))
    }

    /** Caller must stop/flush local playback first. v1 late-turn isolation is not solved here. */
    override fun abort(): Boolean = synchronized(lock) {
        sendText(protocol.abort(machine.hello?.sessionId ?: ""))
    }

    fun sendAudio(bytes: ByteArray): Boolean = synchronized(lock) {
        if (bytes.isEmpty() || bytes.size > 65_536 || !canSend()) false
        else socket!!.send(bytes.toByteString()).also { if (!it) finish(machine.connectionId, "Send failed") }
    }

    override fun disconnect() = synchronized(lock) {
        if (machine.state != TransportState.DISCONNECTED) finish(machine.connectionId, "Local disconnect", DisconnectKind.LOCAL)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            disconnect()
            closed = true
            timer.shutdownNow()
            events.close()
        }
    }

    private fun active(id: Long) = !closed && id == machine.connectionId && machine.state != TransportState.DISCONNECTED

    private fun canSend(): Boolean {
        if (closed || machine.state != TransportState.READY || socket == null) return false
        if (socket!!.queueSize() > 262_144) {
            finish(machine.connectionId, "Outbound queue full", DisconnectKind.OVERLOAD)
            return false
        }
        return true
    }

    private fun sendText(text: String): Boolean =
        if (!canSend()) false else socket!!.send(text).also { if (!it) finish(machine.connectionId, "Send failed") }

    private fun emit(event: SocketEvent) {
        if (events.trySend(event).isFailure) {
            // A stalled consumer must not cause unbounded audio retention.
            socket?.cancel()
            socket = null
            deadline?.cancel(false)
            machine.disconnected(machine.connectionId)
            closed = true
            timer.shutdownNow()
            events.close(IllegalStateException("Inbound queue full"))
        }
    }

    private fun finish(id: Long, reason: String, kind: DisconnectKind = DisconnectKind.NETWORK) {
        if (!active(id)) return
        machine.disconnected(id)
        deadline?.cancel(false)
        deadline = null
        socket?.cancel()
        socket = null
        emit(SocketEvent.Disconnected(id, reason, kind))
    }

    private fun listener(id: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (!active(id) || !machine.opened(id)) webSocket.cancel()
            else if (!webSocket.send(protocol.clientHello())) finish(id, "Hello send failed")
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (!active(id)) return@synchronized
            val event = protocol.parse(text)
            if (machine.state == TransportState.WAITING_HELLO) {
                if (event !is ProtocolEvent.Hello || !machine.hello(id, event)) {
                    finish(id, "Invalid Server Hello", DisconnectKind.PROTOCOL)
                    return@synchronized
                }
                deadline?.cancel(false)
                deadline = null
            } else if (event is ProtocolEvent.Hello) {
                finish(id, "Duplicate Server Hello", DisconnectKind.PROTOCOL)
                return@synchronized
            }
            emit(SocketEvent.Message(id, event))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
            if (!active(id)) return@synchronized
            if (machine.state != TransportState.READY || bytes.size !in 1..65_536) {
                finish(id, "Unexpected audio frame", DisconnectKind.PROTOCOL)
            } else emit(SocketEvent.Message(id, ProtocolEvent.BinaryAudio(bytes.toByteArray())))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            finish(id, "Remote close ($code)", closeKind(code))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            finish(id, "Remote close ($code)", closeKind(code))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            val kind = when {
                response?.code in listOf(401, 403) -> DisconnectKind.AUTH
                t is javax.net.ssl.SSLException -> DisconnectKind.TLS
                response != null && response.code in 400..499 -> DisconnectKind.PROTOCOL
                else -> DisconnectKind.NETWORK
            }
            response?.close()
            finish(id, "WebSocket failure", kind)
        }
    }

    private fun closeKind(code: Int) = when (code) {
        1001, 1006, 1011, 1012, 1013 -> DisconnectKind.NETWORK
        1000 -> DisconnectKind.NORMAL
        else -> DisconnectKind.PROTOCOL
    }
}
