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
package com.embabel.agent.openai

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.ReasoningEffort
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a provider-neutral [LlmOptions.reasoningEffort] puts on the wire for each OpenAI transport,
 * captured by a local server standing in for OpenAI.
 */
class ReasoningEffortWireTest {

    private val mapper = jacksonObjectMapper()
    private val captured = CopyOnWriteArrayList<JsonNode>()
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            captured.add(mapper.readTree(exchange.requestBody.readBytes()))
            val reply = (if (exchange.requestURI.path.endsWith("/responses")) RESPONSE else CHAT_COMPLETION).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private val factory by lazy {
        OpenAiCompatibleModelFactory(baseUrl = "http://localhost:${server.address.port}/v1", apiKey = "test-key")
    }

    private fun send(llm: SpringAiLlmService, options: LlmOptions): JsonNode {
        llm.chatModel.call(Prompt(listOf(UserMessage("hi")), llm.optionsConverter.convertOptions(options, MODEL)))
        return captured.single()
    }

    private fun responses() = factory.openAiResponsesLlm(
        model = MODEL, provider = OpenAiModels.PROVIDER,
    ) as SpringAiLlmService

    private fun chatCompletions(provider: String) = factory.openAiCompatibleLlm(
        model = MODEL, pricingModel = PricingModel.ALL_YOU_CAN_EAT, provider = provider, knowledgeCutoffDate = null,
    ) as SpringAiLlmService

    @Test
    fun `Responses sends the effort as reasoning effort`() {
        val body = send(responses(), LlmOptions.withModel(MODEL).withReasoningEffort(ReasoningEffort.LOW))
        assertEquals("low", body["reasoning"]["effort"].asString())
    }

    @Test
    fun `Chat Completions on OpenAI sends the effort as reasoning_effort`() {
        val body = send(chatCompletions(OpenAiModels.PROVIDER), LlmOptions.withModel(MODEL).withReasoningEffort(ReasoningEffort.LOW))
        assertEquals("low", body["reasoning_effort"].asString())
    }

    @Test
    fun `Chat Completions on another OpenAI-compatible provider does not send it`() {
        val body = send(chatCompletions("Acme"), LlmOptions.withModel(MODEL).withReasoningEffort(ReasoningEffort.LOW))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test
    fun `Responses sends no effort when none is set`() {
        val body = send(responses(), LlmOptions.withModel(MODEL))
        assertFalse(body.path("reasoning").has("effort"), body.toString())
    }

    @Test
    fun `Chat Completions on OpenAI sends no effort when none is set`() {
        val body = send(chatCompletions(OpenAiModels.PROVIDER), LlmOptions.withModel(MODEL))
        assertFalse(body.has("reasoning_effort"), body.toString())
    }

    @Test
    fun `the OpenAI-specific effort takes precedence over the provider-neutral one`() {
        val options = LlmOptions.withModel(MODEL)
            .withReasoningEffort(ReasoningEffort.LOW)
            .withOpenAiReasoningEffort("high")
        assertEquals("high", send(responses(), options)["reasoning"]["effort"].asString())
        captured.clear()
        assertEquals("high", send(chatCompletions(OpenAiModels.PROVIDER), options)["reasoning_effort"].asString())
    }

    private companion object {
        const val MODEL = "gpt-6-luna"

        val RESPONSE = """{"id":"resp_1","object":"response","created_at":0,"model":"$MODEL","status":"completed",
            "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
              "content":[{"type":"output_text","text":"ok","annotations":[]}]}],
            "parallel_tool_calls":false,"tool_choice":"auto","tools":[],"error":null,"incomplete_details":null,
            "instructions":null,"metadata":null,"temperature":null,"top_p":null}"""

        val CHAT_COMPLETION = """{"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"$MODEL",
            "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    }
}
