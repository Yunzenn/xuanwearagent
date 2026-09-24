package com.aiwatch.protocol

import com.google.gson.JsonParser
import kotlin.test.*

class ProtocolTest {
    private val protocol = XiaozhiProtocolV1()
    private val hello = """{"type":"hello","transport":"websocket","session_id":"s","audio_params":{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60}}"""

    @Test fun playbackComesFromServerHello() {
        val event = assertIs<ProtocolEvent.Hello>(protocol.parse(hello))
        assertEquals(24000, event.audio.sampleRate)
        assertEquals("s", event.sessionId)
        val uplink = JsonParser.parseString(protocol.clientHello()).asJsonObject
        assertEquals(16000, uplink["audio_params"].asJsonObject["sample_rate"].asInt)
        assertFalse(uplink.has("features"))
    }

    @Test fun invalidHelloNeverFallsBackToGuessedPlayback() {
        for (message in listOf("{", "[]", "{}", hello.replace("24000", "32000"),
            hello.replace("24000", "24000.5"), hello.replace("24000", "\"24000\""),
            hello.replace("websocket", "mqtt"), hello.replace("opus", "pcm"))) {
            assertIs<ProtocolEvent.Error>(protocol.parse(message))
        }
    }

    @Test fun eventsAndUnknownExtensionsAreTyped() {
        assertEquals(ProtocolEvent.Stt("你好"), protocol.parse("""{"type":"stt","text":"你好"}"""))
        assertEquals(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START), protocol.parse("""{"type":"tts","state":"start"}"""))
        assertEquals(ProtocolEvent.Llm("", "happy"), protocol.parse("""{"type":"llm","emotion":"happy"}"""))
        assertEquals(ProtocolEvent.Unknown("extension"), protocol.parse("""{"type":"extension"}"""))
        assertIs<ProtocolEvent.Error>(protocol.parse("""{"type":"tts","state":"unexpected"}"""))
    }

    @Test fun outboundSessionIsEscaped() {
        val session = "quote\"\\\n"
        for (text in listOf(protocol.listen(session, true), protocol.listen(session, false), protocol.abort(session))) {
            assertEquals(session, JsonParser.parseString(text).asJsonObject["session_id"].asString)
        }
    }

    @Test fun oldSocketCallbacksCannotChangeNewConnection() {
        val transport = TransportStateMachine()
        val first = transport.connect()
        assertFalse(transport.hello(first, assertIs(protocol.parse(hello))))
        assertTrue(transport.opened(first))
        assertTrue(transport.hello(first, assertIs(protocol.parse(hello))))
        assertEquals(TransportState.READY, transport.state)
        transport.disconnected(first)
        assertNull(transport.hello)
        val second = transport.connect()
        assertFalse(transport.opened(first))
        assertFalse(transport.disconnected(first))
        assertTrue(transport.opened(second))
        assertEquals(TransportState.WAITING_HELLO, transport.state)
    }

    @Test fun interruptedQueueAndLateAudioAreRejected() {
        val conversation = ConversationStateMachine()
        conversation.startListening()
        val old = conversation.generation
        assertFalse(conversation.acceptsAudio(old))
        assertFalse(conversation.ttsStarted())
        conversation.stopListening()
        assertTrue(conversation.ttsStarted())
        assertTrue(conversation.acceptsAudio(old))
        conversation.interrupt()
        assertFalse(conversation.acceptsAudio(old))
        conversation.interruptComplete()
        conversation.ttsStopped()
        assertEquals(ConversationState.LISTENING, conversation.state)
        conversation.stopListening()
        assertTrue(conversation.ttsStarted())
        assertFalse(conversation.acceptsAudio(old))
        assertTrue(conversation.acceptsAudio(conversation.generation))
        conversation.disconnected()
        assertEquals(ConversationState.IDLE, conversation.state)
        assertFalse(conversation.acceptsAudio(conversation.generation))
    }
}
