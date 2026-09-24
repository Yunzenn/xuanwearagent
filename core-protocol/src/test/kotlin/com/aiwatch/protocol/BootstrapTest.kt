package com.aiwatch.protocol

import kotlin.test.*

class BootstrapTest {
    private val parser = BootstrapResponseParser()

    @Test fun readyResponseKeepsCredentialsOutOfDiagnostics() {
        val result = assertIs<BootstrapResult.Ready>(parser.parse(
            """{"websocket":{"url":"wss://example.test/ws","token":"private-token"}}""",
        ))
        assertEquals("private-token", result.websocket.token)
        assertEquals("wss://example.test/ws", result.websocket.url)
        assertFalse(result.toString().contains("private-token"))
    }

    @Test fun activationPrecedesConnectionAndUsesDefinedTimeout() {
        val result = assertIs<BootstrapResult.ActivationRequired>(parser.parse(
            """{"activation":{"code":"123456","challenge":"challenge"},"websocket":{"url":"wss://example.test/ws"}}""",
        ))
        assertEquals("123456", result.activation.code)
        assertEquals(30_000, result.activation.timeoutMs)
        assertFalse(result.toString().contains("123456"))
    }

    @Test fun malformedOrUnsupportedResponsesFailClosed() {
        for (body in listOf("{}", "{", "[]", """{"mqtt":{}}""",
            """{"activation":{"timeout_ms":-1}}""",
            """{"websocket":{"url":"https://example.test/ws"}}""",
            """{"websocket":{"url":"wss://user:password@example.test/ws"}}""")) {
            assertIs<BootstrapResult.Invalid>(parser.parse(body))
        }
    }

    @Test fun cleartextRequiresExplicitDevelopmentOptIn() {
        val body = """{"websocket":{"url":"ws://localhost:8000/ws"}}"""
        assertIs<BootstrapResult.Invalid>(parser.parse(body))
        assertIs<BootstrapResult.Ready>(BootstrapResponseParser(true).parse(body))
    }
}
