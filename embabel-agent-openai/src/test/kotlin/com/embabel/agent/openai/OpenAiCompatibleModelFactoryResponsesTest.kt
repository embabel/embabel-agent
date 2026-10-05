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
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.converters.JacksonOutputConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What [OpenAiCompatibleModelFactory.openAiResponsesLlm] puts on the wire, captured by a local
 * server standing in for OpenAI. GPT-6 Luna refuses function tools over Chat Completions unless
 * reasoning is off, and refuses `temperature` and `max_tokens` outright, so these pin the request
 * shape that it accepts.
 */
class OpenAiCompatibleModelFactoryResponsesTest {

    private data class Captured(val path: String, val body: JsonNode)

    private data class Label(val label: String)

    private val mapper = jacksonObjectMapper()
    private val captured = CopyOnWriteArrayList<Captured>()
    private val replies = ArrayDeque<String>()
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            captured += Captured(exchange.requestURI.path, mapper.readTree(exchange.requestBody.readBytes()))
            val reply = (replies.removeFirstOrNull() ?: response(message("ok"))).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private val llm: SpringAiLlmService by lazy {
        OpenAiCompatibleModelFactory(
            baseUrl = "http://localhost:${server.address.port}/v1",
            apiKey = "test-key",
        ).openAiResponsesLlm(
            model = LUNA,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = OpenAiModels.PROVIDER,
            knowledgeCutoffDate = null,
        ) as SpringAiLlmService
    }

    private fun optionsFor(llmOptions: LlmOptions): ChatOptions =
        llm.optionsConverter.convertOptions(llmOptions, LUNA)

    private fun call(options: ChatOptions, vararg messages: Message) =
        llm.chatModel.call(Prompt(messages.toList(), options))

    /** The nested chat observation carries the provider a compatible endpoint was built for. */
    @Test
    fun `the chat observation reports the configured provider`() {
        val providers = CopyOnWriteArrayList<String>()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(object : ObservationHandler<ChatModelObservationContext> {
                override fun supportsContext(context: Observation.Context) = context is ChatModelObservationContext
                override fun onStop(context: ChatModelObservationContext) {
                    providers += context.operationMetadata.provider
                }
            })
        }
        val gateway = OpenAiCompatibleModelFactory(
            baseUrl = "http://localhost:${server.address.port}/v1",
            apiKey = "test-key",
            observationRegistry = registry,
        ).openAiResponsesLlm(
            model = LUNA,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = "Acme Gateway",
            knowledgeCutoffDate = null,
        ) as SpringAiLlmService

        gateway.chatModel.call(Prompt("hi"))

        assertEquals(listOf("Acme Gateway"), providers)
    }

    @Test
    fun `requests go to the Responses endpoint with the model and an output limit, and no sampling parameters`() {
        call(optionsFor(LlmOptions.withModel(LUNA).withTemperature(0.0).withMaxTokens(50)), UserMessage("hi"))

        val request = captured.single()
        assertEquals("/v1/responses", request.path)
        assertEquals(LUNA, request.body["model"].asString())
        assertEquals(50, request.body["max_output_tokens"].asInt())
        assertFalse(request.body.has("temperature"), "Luna accepts only the default temperature")
        assertFalse(request.body.has("max_tokens"))
        assertFalse(request.body.has("reasoning"), "no effort was asked for, so the model's default applies")
    }

    @Test
    fun `an explicit reasoning effort is preserved`() {
        call(optionsFor(LlmOptions.withModel(LUNA).withOpenAiReasoningEffort("low")), UserMessage("hi"))

        assertEquals("low", captured.single().body["reasoning"]["effort"].asString())
    }

    @Test
    fun `a tool call round trip pairs the tool result with its call id`() {
        replies += response(
            """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_weather",
               "arguments":"{\"city\":\"Paris\"}","status":"completed"}"""
        )
        val options = (optionsFor(LlmOptions.withModel(LUNA)) as OpenAiChatOptions).mutate()
            .toolCallbacks(listOf(WEATHER_TOOL))
            .build()
        val question = UserMessage("Weather in Paris?")

        val first = call(options, question)
        val toolCall = first.result.output.toolCalls.single()
        assertEquals("get_weather", toolCall.name)
        assertEquals("call_1", toolCall.id)
        assertEquals("get_weather", captured[0].body["tools"][0]["name"].asString())

        val second = call(
            options,
            question,
            AssistantMessage.builder().toolCalls(listOf(toolCall)).build(),
            ToolResponseMessage.builder()
                .responses(listOf(ToolResponseMessage.ToolResponse("call_1", "get_weather", "sunny")))
                .build(),
        )

        assertEquals("ok", second.result.output.text)
        val input = captured[1].body["input"].toList()
        val functionCall = input.single { it["type"]?.asString() == "function_call" }
        val functionOutput = input.single { it["type"]?.asString() == "function_call_output" }
        assertEquals("call_1", functionCall["call_id"].asString())
        assertEquals("call_1", functionOutput["call_id"].asString())
        assertEquals("sunny", functionOutput["output"].asString())
    }

    @Test
    fun `structured output is requested natively and binds to the requested type`() {
        replies += response(message("""{"label":"Invoice"}"""))
        val converter = JacksonOutputConverter<Label>(Label::class.java, mapper)
        val options = llm.nativeStructuredOutputConfigurer.configure(
            optionsFor(LlmOptions.withModel(LUNA)),
            StructuredOutputRequest(name = "Label", schema = converter.jsonSchema, strict = false),
            llm.nativeSupport,
            llm,
        )

        val text = call(options, UserMessage("Label: quarterly invoice")).result.output.text.orEmpty()

        assertEquals("json_schema", captured.single().body["text"]["format"]["type"].asString())
        assertEquals(Label("Invoice"), converter.convert(text))
    }

    companion object {
        private const val LUNA = "gpt-6-luna"

        private val WEATHER_TOOL = object : ToolCallback {
            override fun getToolDefinition(): ToolDefinition = DefaultToolDefinition.builder()
                .name("get_weather")
                .description("Current weather for a city")
                .inputSchema("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""")
                .build()

            override fun call(toolInput: String): String = "sunny"
        }

        private fun message(text: String): String =
            """{"type":"message","id":"msg_1","role":"assistant","status":"completed",
               "content":[{"type":"output_text","text":${jacksonObjectMapper().writeValueAsString(text)},"annotations":[]}]}"""

        private fun response(vararg output: String): String =
            """{"id":"resp_1","object":"response","created_at":0,"model":"$LUNA","status":"completed",
               "output":[${output.joinToString(",")}],"parallel_tool_calls":false,"tool_choice":"auto",
               "tools":[],"error":null,"incomplete_details":null,"instructions":null,"metadata":null,
               "temperature":null,"top_p":null}"""
    }
}
