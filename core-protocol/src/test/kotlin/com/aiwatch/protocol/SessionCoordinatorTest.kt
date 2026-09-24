package com.aiwatch.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
    private class FakeTransport : SessionTransport {
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        var connections = 0L
        val calls = mutableListOf<String>()
        override suspend fun nextEvent() = events.receive()
        override fun connect(config: WebSocketConfig, identity: DeviceIdentity): Long {
            calls += "connect"; return ++connections
        }
        override fun listen(start: Boolean): Boolean { calls += "listen:$start"; return true }
        override fun abort(): Boolean { calls += "abort"; return true }
        override fun sendAudio(bytes: ByteArray): Boolean { calls += "audio"; return true }
        override fun disconnect() { calls += "disconnect" }
        override fun close() { calls += "close" }
        suspend fun hello(id: Long = connections) = events.send(SocketEvent.Message(id,
            ProtocolEvent.Hello("session", PlaybackAudioConfig("opus", 24000, 1, 60))))
        suspend fun failure(kind: DisconnectKind = DisconnectKind.NETWORK) =
            events.send(SocketEvent.Disconnected(connections, "test", kind))
    }
    private val ready = BootstrapResult.Ready(WebSocketConfig("wss://example.test/ws", null))

    @Test fun readyIsPublishedOnlyAfterPlaybackConsumerInitialization() = runTest {
        val transport = FakeTransport()
        lateinit var coordinator: SessionCoordinator
        var initialized = false
        var captureInvalidations = 0
        coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            onEvent = { event, _ -> if (event is ProtocolEvent.Hello) {
                assertEquals(SessionPhase.CONNECTING, coordinator.state.value.phase)
                initialized = true
            } }, onCaptureInvalidated = { captureInvalidations++ })
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        assertTrue(initialized)
        assertEquals(SessionPhase.READY, coordinator.state.value.phase)
        val beforeCapture = captureInvalidations
        assertNotNull(coordinator.beginCapture())
        assertEquals(beforeCapture, captureInvalidations)
        transport.failure(); runCurrent()
        assertTrue(captureInvalidations > beforeCapture)
        coordinator.close()
    }

    @Test fun uplinkRequiresListeningAndCurrentGeneration() = runTest {
        val transport = FakeTransport()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport)
        coordinator.start(); runCurrent()
        assertNull(coordinator.beginCapture())
        transport.hello(); runCurrent()
        val generation = assertNotNull(coordinator.beginCapture())
        assertFalse(coordinator.sendAudio(byteArrayOf(1), generation - 1))
        assertTrue(coordinator.sendAudio(byteArrayOf(1), generation))
        coordinator.listen(false); runCurrent()
        assertFalse(coordinator.sendAudio(byteArrayOf(1), generation))
        assertEquals(ConversationState.THINKING, coordinator.state.value.conversation)
        coordinator.abort(); runCurrent(); transport.hello(); runCurrent()
        assertNotNull(coordinator.beginCapture())
        assertFalse(coordinator.sendAudio(byteArrayOf(1), generation))
        assertEquals(1, transport.calls.count { it == "audio" })
        coordinator.close()
    }

    @Test fun stableRecoveryRestoresBudgetAcrossRepeatedLongLivedConnections() = runTest {
        val transport = FakeTransport()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            RetryPolicy(maxRetries = 1, stableConnectionMs = 60000, jitter = { 1.0 }))
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        repeat(8) {
            transport.failure(); runCurrent()
            assertEquals(1, coordinator.state.value.retry)
            advanceTimeBy(1000); runCurrent(); transport.hello(); runCurrent()
            assertEquals(1, coordinator.state.value.retry)
            advanceTimeBy(59999); runCurrent()
            assertEquals(1, coordinator.state.value.retry)
            advanceTimeBy(1); runCurrent()
            assertEquals(SessionPhase.READY, coordinator.state.value.phase)
            assertEquals(0, coordinator.state.value.retry)
        }
        assertEquals(9L, transport.connections)
        coordinator.close()
    }

    @Test fun brieflyReadyConnectionsCannotResetRetryBudget() = runTest {
        val transport = FakeTransport()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            RetryPolicy(maxRetries = 1, stableConnectionMs = 60000, jitter = { 1.0 }))
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        transport.failure(); runCurrent(); advanceTimeBy(1000); runCurrent()
        transport.hello(); runCurrent(); advanceTimeBy(59000); runCurrent()
        transport.failure(); runCurrent()
        assertEquals(SessionPhase.ERROR, coordinator.state.value.phase)
        advanceTimeBy(120000); runCurrent()
        assertEquals(SessionPhase.ERROR, coordinator.state.value.phase)
        assertEquals(2L, transport.connections)
        coordinator.close()
    }

    @Test fun retiredConnectionStabilityTimerCannotResetNewRetryWait() = runTest {
        val transport = FakeTransport()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            RetryPolicy(stableConnectionMs = 60000, jitter = { 1.0 }))
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        advanceTimeBy(59999); runCurrent(); transport.failure(); runCurrent()
        advanceTimeBy(1); runCurrent()
        assertEquals(SessionPhase.RETRY_WAIT, coordinator.state.value.phase)
        assertEquals(1, coordinator.state.value.retry)
        coordinator.stop(); advanceTimeBy(120000); runCurrent()
        assertEquals(SessionPhase.STOPPED, coordinator.state.value.phase)
        assertEquals(1L, transport.connections)
        coordinator.close()
    }

    @Test fun interruptDelegatesToInjectedPolicy() = runTest {
        var invocations = 0
        val transport = FakeTransport()
        val policy = object : InterruptPolicy {
            override fun interrupt(context: InterruptContext) {
                invocations++
                HardInterruptPolicy.interrupt(context)
            }
        }
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            interruptPolicy = policy)
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        coordinator.abort(); runCurrent()
        assertEquals(1, invocations)
        assertEquals(2L, transport.connections)
        coordinator.close()
    }

    @Test fun consumerFailureClosesTransportWithoutHangingShutdown() = runTest {
        val transport = FakeTransport()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            onEvent = { _, _ -> error("consumer failed") })
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        assertEquals(SessionPhase.ERROR, coordinator.state.value.phase)
        assertTrue("close" in transport.calls)
        coordinator.stop(); coordinator.close()
    }

    @Test fun activationPollsUntilCredentialsThenReady() = runTest {
        val transport = FakeTransport()
        var polls = 0
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, {
            if (++polls == 1) BootstrapResult.ActivationRequired(ActivationInfo("123456", null, null, 30000)) else ready
        }, transport)
        coordinator.start(); runCurrent()
        assertEquals(SessionPhase.ACTIVATING, coordinator.state.value.phase)
        assertEquals("123456", coordinator.state.value.activationCode)
        assertEquals(0, transport.connections)
        advanceTimeBy(3000); runCurrent()
        assertEquals(SessionPhase.CONNECTING, coordinator.state.value.phase)
        transport.hello(); runCurrent()
        assertEquals(SessionPhase.READY, coordinator.state.value.phase)
        assertNull(coordinator.state.value.activationCode)
        coordinator.close()
    }

    @Test fun reconnectFiltersAlreadyQueuedOldConnectionEvents() = runTest {
        val transport = FakeTransport()
        val received = mutableListOf<ProtocolEvent>()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            RetryPolicy(jitter = { 1.0 }), onEvent = { event, _ -> received += event })
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        transport.failure(); runCurrent()
        assertEquals(SessionPhase.RETRY_WAIT, coordinator.state.value.phase)
        advanceTimeBy(999); runCurrent(); assertEquals(1L, transport.connections)
        advanceTimeBy(1); runCurrent(); assertEquals(2L, transport.connections)
        transport.hello(1); runCurrent()
        assertEquals(SessionPhase.CONNECTING, coordinator.state.value.phase)
        transport.hello(2); runCurrent()
        assertEquals(2, received.size)
        coordinator.close()
    }

    @Test fun explicitStopCancelsScheduledRetry() = runTest {
        val transport = FakeTransport()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport)
        coordinator.start(); runCurrent(); transport.failure(); runCurrent()
        coordinator.stop(); advanceTimeBy(100000); runCurrent()
        assertEquals(1L, transport.connections)
        assertEquals(SessionPhase.STOPPED, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test fun authorizationRefreshesBootstrapOnceThenStops() = runTest {
        val transport = FakeTransport()
        var bootstraps = 0
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { bootstraps++; ready }, transport)
        coordinator.start(); runCurrent(); transport.failure(DisconnectKind.AUTH); runCurrent()
        assertEquals(2, bootstraps)
        transport.failure(DisconnectKind.AUTH); runCurrent()
        assertEquals(SessionPhase.AUTH_REQUIRED, coordinator.state.value.phase)
        advanceTimeBy(100000); runCurrent(); assertEquals(2, bootstraps)
        coordinator.close()
    }

    @Test fun protocolAndTlsFailuresNeverRetry() = runTest {
        for (kind in listOf(DisconnectKind.PROTOCOL, DisconnectKind.TLS)) {
            val transport = FakeTransport()
            val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport)
            coordinator.start(); runCurrent(); transport.failure(kind); runCurrent()
            advanceTimeBy(100000); runCurrent()
            assertEquals(SessionPhase.ERROR, coordinator.state.value.phase)
            assertEquals(1L, transport.connections)
            coordinator.close()
        }
    }

    @Test fun retryBudgetIsFinite() = runTest {
        val transport = FakeTransport()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            RetryPolicy(2) { 1.0 })
        coordinator.start(); runCurrent()
        repeat(3) { transport.failure(); runCurrent(); advanceTimeBy(15000); runCurrent() }
        assertEquals(3L, transport.connections)
        assertEquals(SessionPhase.ERROR, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test fun activationIsBoundedAndChallengeOnlyFailsExplicitly() = runTest {
        for (code in listOf("123456", null)) {
            val transport = FakeTransport()
            val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, {
                BootstrapResult.ActivationRequired(ActivationInfo(code, null, "challenge", 30000))
            }, transport, activationPolicy = ActivationPolicy(maxPolls = 1))
            coordinator.start(); runCurrent(); advanceTimeBy(3000); runCurrent()
            assertEquals(SessionPhase.ERROR, coordinator.state.value.phase)
            assertEquals(0L, transport.connections)
            coordinator.close()
        }
    }

    @Test fun abortFlushesBeforeSendingAndRetiresAmbiguousV1Socket() = runTest {
        val transport = FakeTransport()
        val received = mutableListOf<ProtocolEvent>()
        val coordinator = SessionCoordinator(backgroundScope, { DeviceIdentity.generate() }, { ready }, transport,
            onEvent = { event, _ -> received += event }, flushAudio = { transport.calls += "flush" })
        coordinator.start(); runCurrent(); transport.hello(); runCurrent()
        coordinator.listen(true); coordinator.listen(false); runCurrent()
        transport.events.send(SocketEvent.Message(1, ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START)))
        transport.events.send(SocketEvent.Message(1, ProtocolEvent.BinaryAudio(byteArrayOf(1))))
        runCurrent(); assertEquals(1, received.count { it is ProtocolEvent.BinaryAudio })
        transport.calls.clear(); coordinator.abort(); runCurrent()
        assertEquals(listOf("flush", "abort"), transport.calls.take(2))
        assertEquals(2L, transport.connections)
        transport.events.send(SocketEvent.Message(1, ProtocolEvent.BinaryAudio(byteArrayOf(2))))
        transport.hello(2); runCurrent()
        transport.events.send(SocketEvent.Message(2, ProtocolEvent.BinaryAudio(byteArrayOf(3))))
        runCurrent(); assertEquals(1, received.count { it is ProtocolEvent.BinaryAudio })
        coordinator.close()
    }

    @Test fun identityFailureIsDiagnosticNotAutomaticRegeneration() = runTest {
        val transport = FakeTransport()
        var attempts = 0
        val coordinator = SessionCoordinator(backgroundScope, { attempts++; error("corrupt") }, { ready }, transport)
        coordinator.start(); runCurrent(); advanceTimeBy(100000); runCurrent()
        assertEquals(1, attempts)
        assertEquals(SessionPhase.ERROR, coordinator.state.value.phase)
        assertEquals(0L, transport.connections)
        coordinator.close()
    }
}
