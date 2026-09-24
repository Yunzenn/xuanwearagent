package com.aiwatch.protocol

import androidx.datastore.core.CorruptionException
import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IdentityAndBootstrapTest {
    @TempDir lateinit var directory: File

    @Test fun resetRequiresConfirmationAndPreservesOldIdentity() = runBlocking<Unit> {
        val job = SupervisorJob()
        try {
            val file = File(directory, "identity.bin")
            val store = DeviceIdentityStore(file, CoroutineScope(job + Dispatchers.IO))
            val previous = store.getOrCreate()
            val bytes = file.readBytes()
            assertFailsWith<IllegalArgumentException> { store.resetForRebinding(false) }
            assertEquals(previous, store.getOrCreate())
            val replacement = store.resetForRebinding(true)
            assertNotEquals(previous, replacement)
            assertEquals(replacement, store.getOrCreate())
            assertContentEquals(bytes, directory.listFiles()!!.single { it.name.contains("before-reset") }.readBytes())
        } finally { job.cancelAndJoin() }
    }

    @Test fun explicitResetRecoversCorruptStorage() = runBlocking<Unit> {
        val job = SupervisorJob()
        try {
            val file = File(directory, "identity.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val store = DeviceIdentityStore(file, CoroutineScope(job + Dispatchers.IO))
            assertFailsWith<CorruptionException> { store.getOrCreate() }
            val replacement = store.resetForRebinding(true)
            assertEquals(replacement, store.getOrCreate())
            assertContentEquals(byteArrayOf(1, 2, 3), directory.listFiles()!!.single { it.name.contains("before-reset") }.readBytes())
        } finally { job.cancelAndJoin() }
    }

    @Test fun concurrentCreationAndReopenedStoreKeepSameIdentity() = runBlocking<Unit> {
        val file = File(directory, "identity.bin")
        val firstJob = SupervisorJob()
        val first = DeviceIdentityStore(file, CoroutineScope(firstJob + Dispatchers.IO))
        val identity = try {
            val identities = coroutineScope { (1..32).map { async { first.getOrCreate() } }.awaitAll() }
            assertEquals(1, identities.toSet().size)
            identities.first()
        } finally { firstJob.cancelAndJoin() }
        val secondJob = SupervisorJob()
        try {
            val reopened = DeviceIdentityStore(file, CoroutineScope(secondJob + Dispatchers.IO))
            assertEquals(identity, reopened.getOrCreate())
        } finally { secondJob.cancelAndJoin() }
    }

    @Test fun corruptStorageIsNotReplacedWithANewIdentity() = runBlocking<Unit> {
        val file = File(directory, "broken.bin")
        val bytes = byteArrayOf(1, 2, 3)
        Files.write(file.toPath(), bytes)
        val job = SupervisorJob()
        try {
            val store = DeviceIdentityStore(file, CoroutineScope(job + Dispatchers.IO))
            assertFailsWith<CorruptionException> { store.getOrCreate() }
            assertContentEquals(bytes, Files.readAllBytes(file.toPath()))
        } finally { job.cancelAndJoin() }
    }

    @Test fun independentInstallsHaveDifferentLocallyAdministeredIds() {
        val identities = (1..100).map { DeviceIdentity.generate() }
        assertEquals(100, identities.toSet().size)
        identities.forEach { assertEquals(2, it.deviceId.take(2).toInt(16) and 3) }
    }

    @Test fun httpPostCarriesPersistedIdentityAndParsesReadyResponse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"websocket":{"url":"wss://example.test/ws","token":"token"}}"""))
            val identity = DeviceIdentity.generate()
            val result = BootstrapRepository(allowInsecureDevelopment = true)
                .fetch(server.url("/ota/").toString(), identity, "0.1-test")
            assertIs<BootstrapResult.Ready>(result)
            val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals(identity.deviceId, request.getHeader("Device-Id"))
            assertEquals(identity.clientId, request.getHeader("Client-Id"))
            assertEquals("1", request.getHeader("Activation-Version"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(identity.deviceId, body["mac_address"].asString)
            assertEquals(identity.clientId, body["uuid"].asString)
            assertEquals("0.1-test", body["application"].asJsonObject["version"].asString)
            assertFalse(body.has("flash_size"))
        }
    }

    @Test fun activationHttpErrorsAndOversizeBodiesAreExplicit() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val repository = BootstrapRepository(allowInsecureDevelopment = true)
            val identity = DeviceIdentity.generate()
            val endpoint = server.url("/ota").toString()
            server.enqueue(MockResponse().setBody("""{"activation":{"code":"123456"}}"""))
            assertIs<BootstrapResult.ActivationRequired>(repository.fetch(endpoint, identity, "test"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-error"))
            val error = assertIs<BootstrapResult.Failure>(repository.fetch(endpoint, identity, "test"))
            assertEquals(401, error.httpStatus)
            assertFalse(error.toString().contains("sensitive-error"))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(65_537), 4096))
            assertIs<BootstrapResult.Invalid>(repository.fetch(endpoint, identity, "test"))
        }
    }

    @Test fun redirectIsNotFollowed() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
            val result = BootstrapRepository(allowInsecureDevelopment = true)
                .fetch(server.url("/ota").toString(), DeviceIdentity.generate(), "test")
            assertEquals(302, assertIs<BootstrapResult.Failure>(result).httpStatus)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationPropagatesWhileWaitingForResponse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = async(Dispatchers.IO) {
                BootstrapRepository(allowInsecureDevelopment = true)
                    .fetch(server.url("/ota").toString(), DeviceIdentity.generate(), "test")
            }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            task.cancel()
            withTimeout(2000) { assertFailsWith<CancellationException> { task.await() } }
        }
    }

    @Test fun insecureEndpointIsRejectedBeforeNetworkAccess() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val result = BootstrapRepository().fetch(server.url("/ota").toString(), DeviceIdentity.generate(), "test")
            assertIs<BootstrapResult.Invalid>(result)
            assertEquals(0, server.requestCount)
        }
    }
}
