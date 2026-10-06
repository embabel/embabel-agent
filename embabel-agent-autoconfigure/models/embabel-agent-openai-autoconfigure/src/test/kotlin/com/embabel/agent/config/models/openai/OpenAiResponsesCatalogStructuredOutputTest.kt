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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.NativeStructuredOutputRequest
import com.embabel.agent.spi.loop.RequestAwareLlmMessageSender
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.chat.UserMessage as EmbabelUserMessage
import com.embabel.common.ai.converters.JacksonOutputConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.sun.net.httpserver.HttpServer
import com.openai.client.OpenAIClient
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ToolChoiceOptions
import com.openai.services.blocking.ResponseService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.Optional

/**
 * Runs the real chain — shipped catalog, real configurer, real adapter — so the three stay in
 * step: a `strategy` renamed in the YAML, or a configurer that stopped writing `responseFormat`,
 * would leave a Responses-served model answering in free text with every unit test still green.
 */
class OpenAiResponsesCatalogStructuredOutputTest {

    @Test
    fun `a schema the native support configures reaches the Responses API`() {
        val responsesModel = OpenAiModelLoader().loadAutoConfigMetadata().effectiveModels()
            .first { it.apiFormat == OpenAiApiFormat.RESPONSES }

        val configured = OpenAiNativeStructuredOutputConfigurer.configure(
            options = OpenAiChatOptions.builder().model(responsesModel.modelId).build(),
            structuredOutput = StructuredOutputRequest(
                name = "Answer",
                schema = """{"title":"Answer","type":"object","properties":{"answer":{"type":"string"}}}""",
            ),
            nativeSupport = responsesModel.nativeSupport,
            llm = null,
        )

        val client = mockk<OpenAIClient>()
        val responseService = mockk<ResponseService>()
        val params = slot<ResponseCreateParams>()
        every { client.responses() } returns responseService
        every { responseService.create(capture(params)) } returns emptyResponse(responsesModel.modelId)
        OpenAiResponsesChatModel(client, OpenAiChatOptions.builder().model(responsesModel.modelId).build())
            .call(Prompt(listOf(UserMessage("Hi")), configured))

        val format = params.captured.text().orElseThrow().format().orElseThrow().jsonSchema().orElseThrow()
        assertEquals("Answer", format.name(), "The schema title should name the format")
        assertEquals("object", format.schema()._additionalProperties()["type"]?.asString()?.orElse(null))
    }

    private data class Label(val label: String)

    private data class Tags(val tags: Map<String, String>)

    /**
     * Sends [outputClass]'s schema through a factory-built Responses model that carries the
     * catalog's native support, and returns the request body the endpoint received.
     */
    private fun <T : Any> requestForSchemaOf(outputClass: Class<T>): JsonNode {
        val mapper = jacksonObjectMapper()
        var body: JsonNode? = null
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            body = mapper.readTree(exchange.requestBody.readBytes())
            val reply = RESPONSE_JSON.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            OpenAiCompatibleModelFactory(baseUrl = "http://localhost:${server.address.port}/v1", apiKey = "test-key")
                .openAiResponsesLlm(
                    model = MODEL,
                    pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                    provider = OpenAiModels.PROVIDER,
                    knowledgeCutoffDate = null,
                    nativeSupport = OpenAiModelLoader().loadAutoConfigMetadata().nativeSupportDefaults,
                )
                .let { it.createMessageSender(LlmOptions.withModel(MODEL)) as RequestAwareLlmMessageSender }
                .call(
                    LlmMessageRequest(
                        messages = listOf(EmbabelUserMessage("Label this")),
                        tools = emptyList(),
                        nativeStructuredOutputRequest = NativeStructuredOutputRequest(
                            StructuredOutputRequest(
                                name = outputClass.simpleName,
                                schema = JacksonOutputConverter(outputClass, mapper).jsonSchema,
                            ),
                        ),
                    )
                )
        } finally {
            server.stop(0)
        }
        return body!!
    }

    @Test
    fun `a factory-built Responses model with the catalog's native support sends a compatible schema natively`() {
        val format = requestForSchemaOf(Label::class.java)["text"]["format"]

        assertEquals("json_schema", format["type"].asString())
        assertEquals("label", format["schema"]["required"][0].asString())
    }

    /** The compatibility gate of #2027 applies on this path: an open map is not sent natively. */
    @Test
    fun `a factory-built Responses model falls back to prompt-based output for an incompatible schema`() {
        assertFalse(requestForSchemaOf(Tags::class.java).has("text"), "an incompatible schema must not reach text.format")
    }

    private fun emptyResponse(model: String): Response =
        Response.builder()
            .id("resp_1")
            .createdAt(0.0)
            .model(model)
            .output(emptyList())
            .parallelToolCalls(false)
            .toolChoice(ToolChoiceOptions.AUTO)
            .tools(emptyList())
            .error(Optional.empty())
            .incompleteDetails(Optional.empty())
            .instructions(Optional.empty())
            .metadata(Optional.empty())
            .temperature(Optional.empty())
            .topP(Optional.empty())
            .build()

    companion object {
        private const val MODEL = "gpt-6-luna"

        private const val RESPONSE_JSON =
            """{"id":"resp_1","object":"response","created_at":0,"model":"$MODEL","status":"completed",
               "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
               "content":[{"type":"output_text","text":"{\"label\":\"Invoice\"}","annotations":[]}]}],
               "parallel_tool_calls":false,"tool_choice":"auto","tools":[],"error":null,
               "incomplete_details":null,"instructions":null,"metadata":null,"temperature":null,"top_p":null}"""
    }
}
