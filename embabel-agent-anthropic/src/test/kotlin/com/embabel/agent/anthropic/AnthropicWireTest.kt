/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.anthropic

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress

/**
 * Drives a real [org.springframework.ai.anthropic.AnthropicChatModel] against a local stub of
 * the Messages API, so both the request body Anthropic receives and Spring AI's own parsing of
 * the response are exercised.
 */
class AnthropicWireTest {

    private lateinit var server: HttpServer
    private var responseContent = """[{"type":"text","text":"Hi"}]"""
    private lateinit var request: JsonNode

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/messages") { exchange ->
            request = ObjectMapper().readTree(exchange.requestBody.use { it.readBytes() })
            val body = """
                {"id":"msg_test","type":"message","role":"assistant","content":$responseContent,
                 "model":"test","stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":5,"output_tokens":2}}
            """.trimIndent().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun send(model: String, options: LlmOptions = LlmOptions()) =
        AnthropicModelFactory(apiKey = "test-key", baseUrl = "http://localhost:${server.address.port}")
            .build(model)
            .createMessageSender(options)
            .call(listOf(UserMessage("Reply READY")), emptyList())

    /**
     * Claude 5 thinks by default and, with display "omitted", returns an empty thinking block
     * before the answer. Spring AI turns each block into its own generation.
     */
    @Test
    fun `answer text survives an empty thinking block ahead of it`() {
        responseContent = """[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":"READY"}]"""

        val response = send(AnthropicModels.CLAUDE_OPUS_5_5)

        assertEquals("READY", response.textContent)
        assertEquals("READY", response.message.content)
    }

    /** Reasoning that used up the output limit leaves no answer, and the reasoning is not one. */
    @Test
    fun `a response of thinking alone answers nothing`() {
        responseContent = """[{"type":"thinking","thinking":"secret reasoning","signature":"sig"}]"""

        val response = send(AnthropicModels.CLAUDE_OPUS_5_5)

        assertEquals("", response.message.content)
        assertFalse(response.textContent.contains("secret reasoning"))
    }

    @Test
    fun `summarized thinking does not leak into the answer`() {
        responseContent = """[{"type":"thinking","thinking":"Let me think.","signature":"sig"},{"type":"text","text":"READY"}]"""

        assertEquals("READY", send(AnthropicModels.CLAUDE_SONNET_4_6).textContent)
    }

    @Test
    fun `summarized thinking does not leak into a tool-calling turn`() {
        responseContent = """[{"type":"thinking","thinking":"Let me think.","signature":"sig"},
            {"type":"text","text":"Looking it up."},
            {"type":"tool_use","id":"toolu_1","name":"lookup","input":{}}]"""

        val response = send(AnthropicModels.CLAUDE_SONNET_4_6)

        assertEquals("Looking it up.", response.textContent)
        assertEquals("lookup", (response.message as AssistantMessageWithToolCalls).toolCalls.single().name)
    }

    /**
     * Per-model behaviour from platform.claude.com/docs/en/build-with-claude/thinking
     * ("Configuring thinking" table and "Sampling parameters").
     */
    @Nested
    inner class ThinkingRequest {

        @ParameterizedTest
        @ValueSource(strings = ["claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-4-8", "claude-opus-4-7"])
        fun `a budget becomes adaptive thinking on models that reject budgets`(model: String) {
            send(model, LlmOptions().withThinking(Thinking.withTokenBudget(2000)))

            assertEquals("adaptive", request["thinking"]["type"].asText())
            assertFalse(request["thinking"].has("budget_tokens"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-6", "claude-sonnet-4-6"])
        fun `a budget is sent as is to models that accept budgets`(model: String) {
            send(model, LlmOptions().withThinking(Thinking.withTokenBudget(2000)))

            assertEquals("enabled", request["thinking"]["type"].asText())
            assertEquals(2000, request["thinking"]["budget_tokens"].asInt())
        }

        @ParameterizedTest
        @ValueSource(strings = ["claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-4-5", "claude-opus-5", "claude-sonnet-5"])
        fun `withoutThinking turns thinking off where the model allows it`(model: String) {
            send(model, LlmOptions().withoutThinking())

            assertEquals("disabled", request["thinking"]["type"].asText())
        }

        @ParameterizedTest
        @ValueSource(strings = ["claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1"])
        fun `withoutThinking leaves thinking unset on models that reject disabled`(model: String) {
            send(model, LlmOptions().withoutThinking())

            assertNull(request["thinking"])
        }

        @Test
        fun `thinking extraction leaves the model's own thinking default alone`() {
            send("claude-opus-5", LlmOptions().withThinking(Thinking.withExtraction()))

            assertNull(request["thinking"])
        }

        @ParameterizedTest
        @ValueSource(strings = ["claude-opus-5-5", "claude-haiku-4-5"])
        fun `thinking is unset when the caller says nothing`(model: String) {
            send(model)

            assertNull(request["thinking"])
        }
    }
}
