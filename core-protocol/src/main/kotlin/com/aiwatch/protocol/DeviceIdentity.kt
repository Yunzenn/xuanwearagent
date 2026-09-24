package com.aiwatch.protocol

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.*
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Locally administered random ID, never a hardware MAC. */
data class DeviceIdentity(val deviceId: String, val clientId: String) {
    init {
        require(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(deviceId))
        require(deviceId.take(2).toInt(16) and 3 == 2)
        require(UUID.fromString(clientId).toString() == clientId)
    }

    override fun toString() = "DeviceIdentity(redacted)"

    companion object {
        fun generate(): DeviceIdentity {
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            bytes[0] = ((bytes[0].toInt() or 2) and 0xfe).toByte()
            return DeviceIdentity(bytes.joinToString(":") { "%02x".format(it.toInt() and 255) }, UUID.randomUUID().toString())
        }
    }
}

/** One owner per file/process. Persist before returning; corruption never silently rotates identity. */
class DeviceIdentityStore(
    private val file: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var storeJob = SupervisorJob(scope.coroutineContext[Job])
    private fun createStore() = DataStoreFactory.create(serializer = IdentitySerializer,
        scope = CoroutineScope(scope.coroutineContext + storeJob)) { file }
    private var store = createStore()

    suspend fun getOrCreate(): DeviceIdentity = mutex.withLock {
        requireNotNull(store.updateData { previous -> previous ?: DeviceIdentity.generate() })
    }

    /** Caller must stop its session and obtain explicit rebinding confirmation first. */
    suspend fun resetForRebinding(userConfirmed: Boolean): DeviceIdentity {
        require(userConfirmed) { "Explicit confirmation required" }
        return mutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                storeJob.cancelAndJoin()
                try {
                    if (file.exists()) {
                        val backup = File(file.parentFile, "${file.name}.before-reset-${UUID.randomUUID()}")
                        check(file.renameTo(backup)) { "Cannot preserve previous identity" }
                    }
                } finally {
                    storeJob = SupervisorJob(scope.coroutineContext[Job])
                    store = createStore()
                }
                requireNotNull(store.updateData { previous -> previous ?: DeviceIdentity.generate() })
            }
        }
    }
}

private object IdentitySerializer : Serializer<DeviceIdentity?> {
    override val defaultValue: DeviceIdentity? = null

    override suspend fun readFrom(input: InputStream): DeviceIdentity {
        try {
            val data = DataInputStream(input)
            require(data.readInt() == 1)
            val identity = DeviceIdentity(data.readUTF(), data.readUTF())
            require(data.read() == -1)
            return identity
        } catch (error: EOFException) {
            throw CorruptionException("Truncated identity storage", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("Invalid identity storage", error)
        }
    }

    override suspend fun writeTo(t: DeviceIdentity?, output: OutputStream) {
        val identity = requireNotNull(t)
        DataOutputStream(output).apply {
            writeInt(1)
            writeUTF(identity.deviceId)
            writeUTF(identity.clientId)
            flush()
        }
    }
}
