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

import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.config.models.openai.OpenAiApiFormat
import com.embabel.agent.config.models.openai.OpenAiModelLoader
import com.embabel.agent.config.models.openai.OpenAiResponsesChatModel
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What [OpenAiCompatibleModelFactory.llm] puts on the wire: a key supplied at runtime builds a
 * catalog model the way the catalog declares it, and anything else as before.
 */
class OpenAiCompatibleModelFactoryCatalogTest {

    private data class Captured(val path: String, val body: JsonNode)

    private val mapper = jacksonObjectMapper()
    private val captured = CopyOnWriteArrayList<Captured>()
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            captured += Captured(path, mapper.readTree(exchange.requestBody.readBytes()))
            val reply = (if (path.endsWith("/responses")) RESPONSES_REPLY else CHAT_REPLY).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun factory() = OpenAiCompatibleModelFactory(
        baseUrl = "http://localhost:${server.address.port}/v1",
        apiKey = "test-key",
    )

    private fun llm(model: String, provider: String = OpenAiModels.PROVIDER) =
        factory().llm(model = model, pricingModel = PricingModel.ALL_YOU_CAN_EAT, provider = provider) as SpringAiLlmService

    /** One call at `temperature: 0.0`, as Embabel Me configures most roles; returns the request. */
    private fun callAtZeroTemperature(service: SpringAiLlmService, model: String): Captured {
        val options = service.optionsConverter.convertOptions(LlmOptions.withModel(model).withTemperature(0.0), model)
        service.chatModel.call(Prompt(listOf(UserMessage("hi")), options))
        return captured.single()
    }

    @Test
    fun `a GPT-6 model is served over Responses without the temperature it refuses`() {
        val service = llm(OpenAiModels.GPT_6_ASTRA)

        assertTrue(service.chatModel is OpenAiResponsesChatModel)
        val request = callAtZeroTemperature(service, OpenAiModels.GPT_6_ASTRA)
        assertEquals("/v1/responses", request.path)
        assertFalse(request.body.has("temperature"))
        assertFalse(request.body.has("max_tokens"))
    }

    @Test
    fun `a Chat Completions catalog model stays on Chat Completions`() {
        val service = llm(OpenAiModels.GPT_41_MINI)

        assertTrue(service.chatModel is OpenAiChatModel)
        assertEquals("/v1/chat/completions", callAtZeroTemperature(service, OpenAiModels.GPT_41_MINI).path)
    }

    /** `deepseek-v4-pro` looks like a `*-pro` model by name; only the catalog decides. */
    @Test
    fun `another provider's model is built as before, temperature included`() {
        val service = llm(DeepSeekModels.DEEPSEEK_V4_PRO, DeepSeekModels.PROVIDER)

        assertTrue(service.chatModel is OpenAiChatModel)
        val request = callAtZeroTemperature(service, DeepSeekModels.DEEPSEEK_V4_PRO)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals(0.0, request.body["temperature"].asDouble())
    }

    @Test
    fun `an OpenAI model id under another provider name is not looked up`() {
        assertTrue(llm(OpenAiModels.GPT_6_ASTRA, "Acme Gateway").chatModel is OpenAiChatModel)
    }

    @Test
    fun `a model the catalog does not list is built as before`() {
        assertTrue(llm("gpt-9-preview").chatModel is OpenAiChatModel)
    }

    /** The next family with the same constraint needs a catalog entry and nothing else. */
    @Test
    fun `every Responses model in the catalog is built over Responses`() {
        val responsesModels = OpenAiModelLoader().loadAutoConfigMetadata().models
            .filter { it.apiFormat == OpenAiApiFormat.RESPONSES }
            .map { it.modelId }
        assertTrue(OpenAiModels.GPT_6_LUNA in responsesModels, "catalog: $responsesModels")

        responsesModels.forEach { model ->
            assertTrue(llm(model).chatModel is OpenAiResponsesChatModel, model)
        }
    }

    /** A key validated against GPT-6 proves the transport it will be served on. */
    @Test
    fun `validating against a GPT-6 model probes the Responses endpoint`() {
        val service = factory().buildValidated(
            model = OpenAiModels.GPT_6_LUNA,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = null,
        ) as SpringAiLlmService

        assertEquals(listOf("/v1/responses"), captured.map { it.path })
        assertTrue(service.chatModel is OpenAiResponsesChatModel)
    }

    private companion object {
        val CHAT_REPLY = """
            {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimIndent()

        val RESPONSES_REPLY = """
            {"id":"resp_1","object":"response","created_at":0,"model":"m","status":"completed",
             "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
               "content":[{"type":"output_text","text":"ok","annotations":[]}]}],
             "parallel_tool_calls":false,"tool_choice":"auto","tools":[],"error":null,
             "incomplete_details":null,"instructions":null,"metadata":null,"temperature":null,"top_p":null}
        """.trimIndent()
    }
}
