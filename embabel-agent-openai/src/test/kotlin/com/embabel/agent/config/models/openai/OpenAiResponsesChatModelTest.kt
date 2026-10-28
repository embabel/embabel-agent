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

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.openai.CapabilityAwareOpenAiOptionsConverter
import com.embabel.agent.openai.OpenAiReasoningEffortOptionsConverter
import com.embabel.agent.openai.withOpenAiReasoningEffort
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.streaming.InternalStreamingApi
import com.embabel.agent.spi.support.streaming.StreamingCapabilityDetector
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientImpl
import com.openai.core.ClientOptions
import com.openai.core.http.Headers
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpResponse
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseError
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputRefusal
import com.openai.models.responses.ResponseOutputText
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ResponseStatus
import com.openai.models.responses.ResponseUsage
import com.openai.services.blocking.ResponseService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.MimeTypeUtils
import reactor.core.publisher.Flux
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The adapter is the only thing standing between Embabel and a wire format Spring AI cannot
 * speak, so these tests pin the contract in both directions: what Embabel guarantees to the
 * adapter, and what the surrounding code assumes of what comes back.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 */
@OptIn(InternalStreamingApi::class)
class OpenAiResponsesChatModelTest {

    private val client = mockk<OpenAIClient>()
    private val responseService = mockk<ResponseService>()

    private val model = OpenAiResponsesChatModel(
        client = client,
        defaultOptions = OpenAiChatOptions.builder().model("gpt-5-pro").build(),
    )

    @AfterEach
    fun clearStreamingCapabilityCache() {
        StreamingCapabilityDetector.clearCache()
    }

    /** Stubs the SDK call and returns the params the adapter built. */
    private fun capture(
        prompt: Prompt,
        response: Response = textResponse("ok"),
        chatModel: OpenAiResponsesChatModel = model,
    ): ResponseCreateParams {
        val params = slot<ResponseCreateParams>()
        every { client.responses() } returns responseService
        every { responseService.create(capture(params)) } returns response
        chatModel.call(prompt)
        return params.captured
    }

    private fun respondWith(response: Response) {
        every { client.responses() } returns responseService
        every { responseService.create(any<ResponseCreateParams>()) } returns response
    }

    @Nested
    inner class CustomHeaders {

        @Test
        fun `request headers are forwarded to the SDK request`() {
            val options = OpenAiChatOptions.builder()
                .customHeaders(mapOf("X-Route" to "request-route", "X-Trace-Id" to "trace-1"))
                .build()
            val headers = capture(Prompt(listOf(UserMessage("Hi")), options))._headers()
            assertEquals(listOf("request-route"), headers.values("X-Route"))
            assertEquals(listOf("trace-1"), headers.values("X-Trace-Id"))
        }

        @Test
        fun `requests without custom headers add no request headers`() {
            val prompts = listOf(
                Prompt("Hi"),
                Prompt(listOf(UserMessage("Hi")), ChatOptions.builder().build()),
                Prompt(listOf(UserMessage("Hi")), OpenAiChatOptions.builder().customHeaders(emptyMap()).build()),
            )
            for (prompt in prompts) {
                assertTrue(capture(prompt)._headers().isEmpty())
            }
        }

        @Test
        fun `forwarding request headers does not mutate options`() {
            val requestHeaders = mapOf("x-route" to "request-route", "X-Trace-Id" to "trace-1")
            val options = OpenAiChatOptions.builder().customHeaders(requestHeaders).build()
            val headers = capture(Prompt(listOf(UserMessage("Hi")), options))._headers()
            assertEquals(listOf("request-route"), headers.values("X-Route"))
            assertEquals(listOf("trace-1"), headers.values("X-Trace-Id"))
            assertEquals(requestHeaders, options.customHeaders)
        }

        @Test
        fun `headers do not leak into later calls on the same model`() {
            val firstOptions = OpenAiChatOptions.builder().customHeaders(mapOf("X-Route" to "first")).build()
            val secondOptions = OpenAiChatOptions.builder().customHeaders(mapOf("X-Route" to "second")).build()
            val first = capture(Prompt(listOf(UserMessage("Hi")), firstOptions))._headers()
            val second = capture(Prompt(listOf(UserMessage("Hi")), secondOptions))._headers()
            val absent = capture(Prompt("Hi"))._headers()
            val empty = capture(
                Prompt(
                    listOf(UserMessage("Hi")),
                    OpenAiChatOptions.builder().customHeaders(emptyMap()).build(),
                )
            )._headers()
            assertEquals(listOf("first"), first.values("X-Route"))
            assertEquals(listOf("second"), second.values("X-Route"))
            assertTrue(absent.isEmpty())
            assertTrue(empty.isEmpty())
        }

        @Test
        fun `request headers override client headers only for that request`() {
            val requests = mutableListOf<HttpRequest>()
            val transport = mockk<HttpClient>(relaxUnitFun = true)
            every { transport.execute(any(), any()) } answers {
                requests.add(firstArg())
                mockk<HttpResponse>(relaxUnitFun = true) {
                    every { statusCode() } returns 200
                    every { headers() } returns Headers.builder().put("Content-Type", "application/json").build()
                    every { body() } answers {
                        """{"id":"resp_1","object":"response","model":"gpt-5-pro","status":"completed","output":[]}"""
                            .byteInputStream()
                    }
                }
            }
            // Exercise the SDK's real request construction without opening a socket in CI.
            val sdkClient = OpenAIClientImpl(
                ClientOptions.builder()
                    .httpClient(transport)
                    .apiKey("test-key")
                    .putHeader("X-Client", "client-default")
                    .putHeader("X-Route", "client-route")
                    .maxRetries(0)
                    .build()
            )
            try {
                val defaults = OpenAiChatOptions.builder().model("gpt-5-pro").build()
                val chatModel = OpenAiResponsesChatModel(sdkClient, defaults)
                val options = OpenAiChatOptions.builder()
                    .customHeaders(mapOf("x-route" to "request-route", "X-Trace-Id" to "trace-1")).build()
                chatModel.call(Prompt(listOf(UserMessage("Hi")), options))
                chatModel.call(Prompt("Hi"))
                chatModel.call(Prompt(listOf(UserMessage("Hi")), ChatOptions.builder().build()))
                chatModel.call(
                    Prompt(
                        listOf(UserMessage("Hi")),
                        OpenAiChatOptions.builder().customHeaders(emptyMap()).build(),
                    )
                )
            } finally {
                sdkClient.close()
            }
            assertEquals(4, requests.size)
            assertEquals(listOf("request-route"), requests[0].headers.values("X-Route"))
            assertEquals(listOf("trace-1"), requests[0].headers.values("X-Trace-Id"))
            requests.drop(1).forEach {
                assertEquals(listOf("client-route"), it.headers.values("X-Route"))
                assertTrue(it.headers.values("X-Trace-Id").isEmpty())
            }
            requests.forEach {
                assertEquals(listOf("client-default"), it.headers.values("X-Client"))
                assertEquals(listOf("Bearer test-key"), it.headers.values("Authorization"))
            }
        }

        @Test
        fun `concurrent calls retain their own headers`() {
            val barrier = CyclicBarrier(2)
            val captured = ConcurrentLinkedQueue<Headers>()
            every { client.responses() } returns responseService
            every { responseService.create(any<ResponseCreateParams>()) } answers {
                val params = firstArg<ResponseCreateParams>()
                barrier.await(5, TimeUnit.SECONDS)
                captured.add(params._headers())
                textResponse("ok")
            }
            val executor = Executors.newFixedThreadPool(2)
            val asyncer: Asyncer = ExecutorAsyncer(executor)
            try {
                val calls = listOf("first", "second").map { value ->
                    asyncer.async {
                        val options = OpenAiChatOptions.builder().customHeaders(mapOf("X-Route" to value)).build()
                        model.call(Prompt(listOf(UserMessage("Hi")), options))
                    }
                }
                calls.forEach { it.get(10, TimeUnit.SECONDS) }
                assertEquals(setOf(listOf("first"), listOf("second")), captured.map { it.values("X-Route") }.toSet())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Nested
    inner class RequestMapping {

        @Test
        fun `reasoning is omitted by default and forwarded for configured efforts`() {
            val converter = OpenAiReasoningEffortOptionsConverter(CapabilityAwareOpenAiOptionsConverter())
            val absent = converter.convertOptions(LlmOptions(), "gpt-5-pro")
            assertTrue(capture(Prompt(listOf(UserMessage("Hi")), absent)).reasoning().isEmpty)

            for (effort in listOf("none", "low", "medium")) {
                val options = converter.convertOptions(LlmOptions().withOpenAiReasoningEffort(effort), "gpt-5-pro")
                val params = capture(Prompt(listOf(UserMessage("Hi")), options))
                assertEquals(effort, params.reasoning().orElseThrow().effort().orElseThrow().asString())
            }
        }

        /**
         * The Responses API has no system role: a system message carried as an input item is
         * treated as ordinary user text, quietly losing its authority over the model.
         */
        @Test
        fun `system messages become instructions, everything else becomes input`() {
            val params = capture(
                Prompt(
                    listOf(SystemMessage("You are terse."), UserMessage("Hello")),
                    OpenAiChatOptions.builder().model("gpt-5-pro").build(),
                )
            )

            assertEquals("You are terse.", params.instructions().orElse(null))

            val input = params.input().orElseThrow().asResponse()
            assertEquals(1, input.size, "The system message must not also appear as input")
            assertEquals("Hello", input.single().easyInputMessage().orElseThrow().content().asTextInput())
        }

        /**
         * Embabel drives its own tool loop: it replays the assistant's tool calls and their results
         * on the next turn. The Responses API pairs the two by `call_id`, so a lost or renamed id
         * strands the loop — the model reissues the same call forever.
         */
        @Test
        fun `tool calls and their results are paired by call id`() {
            val toolCall = AssistantMessage.ToolCall("call_42", "function", "lookup", """{"q":"x"}""")
            val params = capture(
                Prompt(
                    listOf(
                        UserMessage("Find x"),
                        AssistantMessage.builder().content("").toolCalls(listOf(toolCall)).build(),
                        ToolResponseMessage.builder()
                            .responses(listOf(ToolResponseMessage.ToolResponse("call_42", "lookup", "found")))
                            .build(),
                    ),
                    OpenAiChatOptions.builder().model("gpt-5-pro").build(),
                )
            )

            val input = params.input().orElseThrow().asResponse()

            val call = input.mapNotNull { it.functionCall().orElse(null) }.single()
            assertEquals("call_42", call.callId())
            assertEquals("lookup", call.name())
            assertEquals("""{"q":"x"}""", call.arguments())

            val output = input.mapNotNull { it.functionCallOutput().orElse(null) }.single()
            assertEquals("call_42", output.callId(), "call_id must survive the round trip")
            assertTrue(output.output().toString().contains("found"), "Tool result reaches the model")
        }

        @Test
        fun `tool definitions are forwarded as function tools`() {
            val params = capture(
                Prompt(
                    listOf(UserMessage("Hi")),
                    OpenAiChatOptions.builder()
                        .model("gpt-5-pro")
                        .toolCallbacks(listOf(toolCallback("lookup", "Looks things up")))
                        .build(),
                )
            )

            val tool = params.tools().orElseThrow().single().function().orElseThrow()
            assertEquals("lookup", tool.name())
            assertEquals("Looks things up", tool.description().orElse(null))
            assertNotNull(tool.parameters(), "A tool without parameters cannot be called")
        }

        /**
         * `OpenAiNativeStructuredOutputConfigurer` writes the schema as a Chat Completions
         * `response_format`. The Responses API carries it under `text.format` instead, and unlike
         * Chat Completions it requires a name — so the adapter has to supply one.
         */
        @Test
        fun `native structured output is rewritten as a text format`() {
            val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""
            val params = capture(
                Prompt(
                    listOf(UserMessage("Hi")),
                    OpenAiChatOptions.builder()
                        .model("gpt-5-pro")
                        .responseFormat(
                            OpenAiChatModel.ResponseFormat.builder()
                                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                                .jsonSchema(schema)
                                .build()
                        )
                        .build(),
                )
            )

            val format = params.text().orElseThrow().format().orElseThrow().jsonSchema().orElseThrow()
            assertTrue(format.name().isNotBlank(), "The Responses API rejects an unnamed json_schema")

            val properties = format.schema()._additionalProperties()
            assertEquals("object", properties["type"]?.asString()?.orElse(null))
            assertTrue(
                properties["properties"].toString().contains("answer"),
                "The schema must survive the format change intact, got: $properties",
            )
        }

        /** The name OpenAI accepts, for a schema titled [title], or none when it is null. */
        private fun schemaNameFor(title: String?): String {
            val titleField = title?.let { """"title":"$it",""" } ?: ""
            val params = capture(
                Prompt(
                    listOf(UserMessage("Hi")),
                    OpenAiChatOptions.builder()
                        .model("gpt-5-pro")
                        .responseFormat(
                            OpenAiChatModel.ResponseFormat.builder()
                                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                                .jsonSchema("""{$titleField"type":"object"}""")
                                .build()
                        )
                        .build(),
                )
            )
            return params.text().orElseThrow().format().orElseThrow().jsonSchema().orElseThrow().name()
        }

        /**
         * A title OpenAI would reject costs the whole call, and it is never worth an answer: the
         * name is a label echoed back, not something the model reasons over. So it is folded, and
         * a schema with no title still gets one, since the API refuses an unnamed schema either way.
         */
        @Test
        fun `schema names are folded to what the api accepts`() {
            assertEquals("Answer", schemaNameFor("Answer"))
            assertEquals("com_example_Answer", schemaNameFor("com.example.Answer"))
            assertEquals("List_Answer_", schemaNameFor("List<Answer>"))
            assertEquals("response", schemaNameFor(null), "The API refuses an unnamed schema")
            assertEquals("response", schemaNameFor(""), "An empty title names nothing")
        }

        /** OpenAI rejects a schema name over 64 characters, and a qualified generic title gets there. */
        @Test
        fun `schema names are cut to the length the api accepts`() {
            val title = "com.example.orders.fulfilment.List<com.example.orders.fulfilment.ShipmentLine>"

            assertEquals("com_example_orders_fulfilment_List_com_example_orders_fulfilment", schemaNameFor(title))
        }

        /** A response_format that is not a JSON schema has no Responses equivalent to carry. */
        @Test
        fun `non schema response formats are dropped rather than mistranslated`() {
            val params = capture(
                Prompt(
                    listOf(UserMessage("Hi")),
                    OpenAiChatOptions.builder()
                        .model("gpt-5-pro")
                        .responseFormat(
                            OpenAiChatModel.ResponseFormat.builder()
                                .type(OpenAiChatModel.ResponseFormat.Type.TEXT)
                                .build()
                        )
                        .build(),
                )
            )

            assertTrue(params.text().isEmpty)
        }

        /**
         * The pro models are served by [Gpt5ChatOptionsConverter], which carries the caller's token
         * limit on `maxCompletionTokens` because the GPT-5 family rejects `max_tokens`. Reading only
         * the latter here would silently drop every limit the caller set.
         */
        @Test
        fun `token limit is read from either options field`() {
            assertEquals(
                256L,
                capture(
                    Prompt(
                        listOf(UserMessage("Hi")),
                        OpenAiChatOptions.builder().model("gpt-5-pro").maxCompletionTokens(256).build(),
                    )
                ).maxOutputTokens().orElse(null),
            )

            assertEquals(
                128L,
                capture(
                    Prompt(
                        listOf(UserMessage("Hi")),
                        OpenAiChatOptions.builder().model("gpt-5-pro").maxTokens(128).build(),
                    )
                ).maxOutputTokens().orElse(null),
                "Options built elsewhere still use maxTokens",
            )
        }

        /**
         * Dropped quietly, the model answers in free text and the failure surfaces later as a
         * binding error naming the wrong culprit.
         */
        @Test
        fun `an unparseable structured output schema is refused, not silently downgraded`() {
            respondWith(textResponse("ok"))

            val failure = assertThrows(IllegalArgumentException::class.java) {
                model.call(
                    Prompt(
                        listOf(UserMessage("Hi")),
                        OpenAiChatOptions.builder()
                            .model("gpt-5-pro")
                            .responseFormat(
                                OpenAiChatModel.ResponseFormat.builder()
                                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                                    .jsonSchema("{ not json")
                                    .build()
                            )
                            .build(),
                    )
                )
            }

            assertTrue(
                failure.message.orEmpty().contains("schema", ignoreCase = true),
                "The failure must name what could not be read, got: ${failure.message}",
            )
        }

        /** An empty parameter schema tells the model the tool takes no arguments — it never does. */
        @Test
        fun `an unparseable tool schema is refused rather than sent as a parameterless tool`() {
            respondWith(textResponse("ok"))

            val failure = assertThrows(IllegalArgumentException::class.java) {
                model.call(
                    Prompt(
                        listOf(UserMessage("Hi")),
                        OpenAiChatOptions.builder()
                            .model("gpt-5-pro")
                            .toolCallbacks(listOf(toolCallback("lookup", "Looks things up", "{ not json")))
                            .build(),
                    )
                )
            }

            assertTrue(
                failure.message.orEmpty().contains("lookup"),
                "The failure must name the offending tool, got: ${failure.message}",
            )
        }

        private fun userContentOf(text: String?, vararg media: Media): List<ResponseInputContent> {
            val message = UserMessage.builder().text(text ?: "").media(media.toList()).build()
            val params = capture(Prompt(listOf(message), OpenAiChatOptions.builder().model("gpt-6-astra").build()))
            return params.input().orElseThrow().asResponse().single()
                .easyInputMessage().orElseThrow().content().asResponseInputMessageContentList()
        }

        private fun imageUrlOf(part: ResponseInputContent): String =
            part.inputImage().orElseThrow().imageUrl().orElseThrow()

        /** Image analysis and chat attachments reach GPT-6 over this transport, so images must arrive. */
        @Test
        fun `image bytes are sent as a data url after the text`() {
            val parts = userContentOf(
                "What is in this image?",
                Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(ByteArrayResource(byteArrayOf(1, 2, 3))).build(),
            )

            assertEquals(2, parts.size)
            assertEquals("What is in this image?", parts[0].inputText().orElseThrow().text())
            assertEquals("data:image/png;base64,AQID", imageUrlOf(parts[1]))
        }

        @Test
        fun `an image url is passed through and several images keep their order`() {
            val parts = userContentOf(
                "Compare",
                Media.builder().mimeType(MimeTypeUtils.IMAGE_JPEG).data("https://example.com/a.jpg").build(),
                Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(byteArrayOf(9)).build(),
            )

            assertEquals(listOf("https://example.com/a.jpg", "data:image/png;base64,CQ=="), parts.drop(1).map(::imageUrlOf))
        }

        @Test
        fun `an image without text is sent as the image alone`() {
            val parts = userContentOf(
                null,
                Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(byteArrayOf(1)).build(),
            )

            assertEquals(1, parts.size)
            assertTrue(parts.single().isInputImage())
        }

        /** A document sent as text alone yields a confident answer about nothing. */
        @Test
        fun `non image media is refused rather than silently dropped`() {
            respondWith(textResponse("ok"))

            assertThrows(UnsupportedOperationException::class.java) {
                model.call(
                    Prompt(
                        listOf(
                            UserMessage.builder()
                                .text("Summarise this")
                                .media(
                                    listOf(
                                        Media.builder()
                                            .mimeType(MimeTypeUtils.parseMimeType("application/pdf"))
                                            .data(ByteArrayResource(byteArrayOf(1, 2, 3)))
                                            .build()
                                    )
                                )
                                .build()
                        ),
                        OpenAiChatOptions.builder().model("gpt-5-pro").build(),
                    )
                )
            }
        }

        /**
         * `SpringAiLlmService.convertOptions` stamps the configured model onto every request, but
         * the converters it delegates to do not set one, so the default has to hold the floor.
         */
        @Test
        fun `request model comes from the prompt, falling back to the configured default`() {
            assertEquals(
                "gpt-5.4-pro",
                capture(
                    Prompt(
                        listOf(UserMessage("Hi")),
                        OpenAiChatOptions.builder().model("gpt-5.4-pro").build(),
                    )
                ).model().orElseThrow().asString(),
            )

            assertEquals(
                "gpt-5-pro",
                capture(Prompt(listOf(UserMessage("Hi")))).model().orElseThrow().asString(),
            )
        }
    }

    @Nested
    inner class ResponseMapping {

        @Test
        fun `text output becomes the assistant message`() {
            respondWith(textResponse("READY"))

            assertEquals("READY", model.call(Prompt("Hi")).result.output.text)
        }

        /**
         * `SpringAiLlmMessageSender.findGenerationWithToolCalls` looks for tool calls on the
         * generations to decide whether the loop continues.
         */
        @Test
        fun `function call output becomes a tool call embabel can dispatch`() {
            respondWith(
                response(
                    ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                            .callId("call_7")
                            .name("lookup")
                            .arguments("""{"q":"x"}""")
                            .build()
                    )
                )
            )

            val toolCall = model.call(Prompt("Hi")).result.output.toolCalls.single()
            assertEquals("call_7", toolCall.id)
            assertEquals("function", toolCall.type, "Spring AI dispatches on this discriminator")
            assertEquals("lookup", toolCall.name)
            assertEquals("""{"q":"x"}""", toolCall.arguments)
        }

        /**
         * `SpringAiLlmMessageSender` dereferences `response.result` without a null check. A
         * reasoning model can return output holding nothing but reasoning items — no message, no
         * tool call — for instance when it exhausts its output budget while thinking. Returning an
         * empty generation list there turns a recoverable empty answer into an NPE inside Embabel.
         */
        @Test
        fun `reasoning-only output still yields one generation`() {
            respondWith(
                response(
                    ResponseOutputItem.ofReasoning(
                        ResponseReasoningItem.builder().id("rs_1").summary(emptyList()).build()
                    )
                )
            )

            val response = model.call(Prompt("Hi"))

            assertEquals(1, response.results.size)
            assertNotNull(response.result.output, "Embabel dereferences this without a null check")
            assertEquals("", response.result.output.text)
            assertTrue(response.result.output.toolCalls.isEmpty())
        }

        /** Without usage, every pro-model call is priced at zero. */
        @Test
        fun `token usage is carried through for cost tracking`() {
            respondWith(textResponse("ok", usage(inputTokens = 120, outputTokens = 34)))

            val usage = model.call(Prompt("Hi")).metadata.usage
            assertEquals(120, usage.promptTokens)
            assertEquals(34, usage.completionTokens)
            assertEquals(154, usage.totalTokens)
        }

        /** OpenAI's own ids deserialize to a union variant, not to a plain string. */
        @Test
        fun `model name is reported in the response metadata`() {
            respondWith(textResponse("ok"))

            assertEquals("gpt-5-pro", model.call(Prompt("Hi")).metadata.model)
        }
    }

    /**
     * The Responses API reports a refusal or a truncation *inside a 200*, so nothing throws on its
     * own. Read only `output`, and a failed call is indistinguishable from a model that had nothing
     * to say.
     */
    @Nested
    inner class FailureAndTruncation {

        @Test
        fun `a failed response is raised, not passed off as an empty answer`() {
            respondWith(
                response(
                    status = ResponseStatus.FAILED,
                    error = ResponseError.builder()
                        .code(ResponseError.Code.SERVER_ERROR)
                        .message("The model failed to generate a response.")
                        .build(),
                )
            )

            val failure = assertThrows(NonTransientAiException::class.java) {
                model.call(Prompt("Hi"))
            }

            assertTrue(
                failure.message.orEmpty().contains("The model failed to generate a response."),
                "OpenAI's own explanation is the only useful part, got: ${failure.message}",
            )
        }

        /** A refusal comes back `completed` with no output text — indistinguishable from silence. */
        @Test
        fun `a refusal is raised, not passed off as an empty answer`() {
            respondWith(
                response(
                    ResponseOutputItem.ofMessage(
                        ResponseOutputMessage.builder()
                            .id("msg_1")
                            .addContent(ResponseOutputRefusal.builder().refusal("I cannot help with that.").build())
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .build()
                    ),
                    status = ResponseStatus.COMPLETED,
                )
            )

            val failure = assertThrows(NonTransientAiException::class.java) { model.call(Prompt("Hi")) }

            assertTrue(
                failure.message.orEmpty().contains("I cannot help with that."),
                "The model's own wording is the only useful part, got: ${failure.message}",
            )
        }

        /** Cancellation is another 200 carrying no answer. */
        @Test
        fun `a cancelled response is raised rather than returned empty`() {
            respondWith(response(status = ResponseStatus.CANCELLED))

            assertThrows(NonTransientAiException::class.java) { model.call(Prompt("Hi")) }
        }

        /**
         * `max_output_tokens` is spent on reasoning too, so a pro model runs out mid-thought
         * routinely. The partial answer is worth keeping — but silently, it reads as a complete one.
         */
        @Test
        fun `a truncated response keeps its text and reports why it stopped`() {
            respondWith(
                textResponse("As far as I got").toBuilder()
                    .status(ResponseStatus.INCOMPLETE)
                    .incompleteDetails(
                        Response.IncompleteDetails.builder()
                            .reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                            .build()
                    )
                    .build()
            )

            val generation = model.call(Prompt("Hi")).result

            assertEquals("As far as I got", generation.output.text, "Partial output is still useful")
            assertEquals(
                "length",
                generation.metadata.finishReason,
                "Truncation is reported in the vocabulary the rest of Spring AI uses",
            )
        }

        /**
         * A call cut off by the output limit can end in a function call whose arguments are a
         * fragment of JSON. Dispatching it runs the tool on input the model never finished.
         */
        @Test
        fun `a function call the output limit cut short is not dispatched`() {
            respondWith(
                response(
                    ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                            .callId("call_7")
                            .name("lookup")
                            .arguments("""{"q":"x""")
                            .status(ResponseFunctionToolCall.Status.INCOMPLETE)
                            .build()
                    ),
                    status = ResponseStatus.INCOMPLETE,
                    incompleteDetails = Response.IncompleteDetails.builder()
                        .reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                        .build(),
                )
            )

            val generation = model.call(Prompt("Hi")).result

            assertTrue(generation.output.toolCalls.isEmpty(), "A partial call must not reach the tool")
            assertEquals("length", generation.metadata.finishReason)
        }

        @Test
        fun `a content filtered response is reported as such rather than as an empty answer`() {
            respondWith(
                response(
                    status = ResponseStatus.INCOMPLETE,
                    incompleteDetails = Response.IncompleteDetails.builder()
                        .reason(Response.IncompleteDetails.Reason.CONTENT_FILTER)
                        .build(),
                )
            )

            assertEquals("content_filter", model.call(Prompt("Hi")).result.metadata.finishReason)
        }

        @Test
        fun `a completed response reports its finish reason too`() {
            respondWith(textResponse("ok").toBuilder().status(ResponseStatus.COMPLETED).build())

            assertEquals("stop", model.call(Prompt("Hi")).result.metadata.finishReason)
        }

        /** Spring AI's convention: a turn that ends in tool calls did not stop, it yielded. */
        @Test
        fun `a response ending in tool calls is reported as such`() {
            respondWith(
                response(
                    ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                            .callId("call_7")
                            .name("lookup")
                            .arguments("{}")
                            .build()
                    ),
                    status = ResponseStatus.COMPLETED,
                )
            )

            assertEquals("tool_calls", model.call(Prompt("Hi")).result.metadata.finishReason)
        }
    }

    @Nested
    inner class ContractWithSurroundingCode {

        /**
         * Code compiled against the published adapter links to these two constructors: the
         * four-argument one from Java, and Kotlin's default-argument one, which takes a mask of
         * the defaulted parameters and a marker. Removing either is a NoSuchMethodError there.
         */
        @Test
        fun `the published constructors still link`() {
            val type = OpenAiResponsesChatModel::class.java
            val options = OpenAiChatOptions.builder().model("gpt-5-pro").build()

            type.getConstructor(
                OpenAIClient::class.java, OpenAiChatOptions::class.java,
                ObservationRegistry::class.java, ObjectMapper::class.java,
            ).newInstance(client, options, ObservationRegistry.NOOP, ObjectMapper())
            val withDefaults = type.getConstructor(
                OpenAIClient::class.java, OpenAiChatOptions::class.java,
                ObservationRegistry::class.java, ObjectMapper::class.java,
                Int::class.javaPrimitiveType, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            ).newInstance(client, options, null, null, 0b1100, null)

            assertEquals(options.model, (withDefaults.defaultOptions as OpenAiChatOptions).model)
        }

        /** `StreamingCapabilityVerifier` probes streaming support by calling [stream] and catching this. */
        @Test
        fun `stream signals unsupported in the one way the verifier understands`() {
            assertThrows(UnsupportedOperationException::class.java) {
                model.stream(Prompt("Hi"))
            }
        }

        @Test
        fun `LlmService reports the Responses model as non-streaming after a single probe`() {
            val counting = ProbeCountingChatModel(model)
            val service = SpringAiLlmService(
                name = "gpt-5-pro",
                provider = "openai",
                chatModel = counting,
            )

            assertFalse(service.supportsStreaming())
            assertFalse(service.supportsStreaming())
            assertEquals(1, counting.streamCalls.get())
        }

        @Test
        fun `default options expose the configured model`() {
            assertEquals("gpt-5-pro", model.defaultOptions.model)
        }

        /**
         * Embabel's `embabel.llm` and `embabel.tool_loop` spans are deliberately thin, because the
         * generation content is expected on the nested Spring AI ChatModel observation. Without one,
         * pro-model calls lose their prompt, completion and token counts from every trace.
         */
        @Test
        fun `the call is observed so traces keep their generation content`() {
            val observed = mutableListOf<String>()
            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context) = true
                        override fun onStop(context: Observation.Context) {
                            observed += context.name
                        }
                    }
                )
            }
            val observedModel = OpenAiResponsesChatModel(
                client = client,
                defaultOptions = OpenAiChatOptions.builder().model("gpt-5-pro").build(),
                observationRegistry = registry,
            )
            respondWith(textResponse("ok"))

            observedModel.call(Prompt("Hi"))

            assertEquals(1, observed.size, "Exactly one chat model observation per call")
            assertTrue(observed.single().startsWith("gen_ai"), "Observed as: ${observed.single()}")
        }

        /** A failed call must not leave the observation open, or the trace never closes. */
        @Test
        fun `a failing call still closes its observation`() {
            val errors = mutableListOf<Throwable?>()
            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context) = true
                        override fun onStop(context: Observation.Context) {
                            errors += context.error
                        }
                    }
                )
            }
            val observedModel = OpenAiResponsesChatModel(
                client = client,
                defaultOptions = OpenAiChatOptions.builder().model("gpt-5-pro").build(),
                observationRegistry = registry,
            )
            every { client.responses() } returns responseService
            every { responseService.create(any<ResponseCreateParams>()) } throws IllegalStateException("boom")

            assertThrows(IllegalStateException::class.java) { observedModel.call(Prompt("Hi")) }

            assertEquals(1, errors.size, "The observation must be stopped even on failure")
            assertNotNull(errors.single(), "The failure must be recorded on the observation")
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private fun toolCallback(
        name: String,
        description: String,
        inputSchema: String = """{"type":"object","properties":{"q":{"type":"string"}}}""",
    ) =
        object : org.springframework.ai.tool.ToolCallback {
            override fun getToolDefinition(): ToolDefinition =
                ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build()

            override fun call(toolInput: String): String = "unused"
        }

    private fun usage(inputTokens: Long, outputTokens: Long): ResponseUsage =
        ResponseUsage.builder()
            .inputTokens(inputTokens)
            .inputTokensDetails(ResponseUsage.InputTokensDetails.builder().cachedTokens(0).cacheWriteTokens(0).build())
            .outputTokens(outputTokens)
            .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder().reasoningTokens(0).build())
            .totalTokens(inputTokens + outputTokens)
            .build()

    private fun textResponse(text: String, usage: ResponseUsage? = null): Response =
        response(
            ResponseOutputItem.ofMessage(
                ResponseOutputMessage.builder()
                    .id("msg_1")
                    .addContent(ResponseOutputText.builder().text(text).annotations(emptyList()).build())
                    .status(ResponseOutputMessage.Status.COMPLETED)
                    .build()
            ),
            usage = usage,
        )

    private fun response(
        vararg output: ResponseOutputItem,
        usage: ResponseUsage? = null,
        status: ResponseStatus? = null,
        error: ResponseError? = null,
        incompleteDetails: Response.IncompleteDetails? = null,
    ): Response =
        Response.builder()
            .id("resp_1")
            .createdAt(0.0)
            .model("gpt-5-pro")
            .output(output.toList())
            .parallelToolCalls(false)
            .toolChoice(com.openai.models.responses.ToolChoiceOptions.AUTO)
            .tools(emptyList())
            // The SDK treats these as required even when absent on the wire.
            .error(Optional.ofNullable(error))
            .incompleteDetails(Optional.ofNullable(incompleteDetails))
            .instructions(Optional.empty())
            .metadata(Optional.empty())
            .temperature(Optional.empty())
            .topP(Optional.empty())
            .apply { usage?.let { usage(it) } }
            .apply { status?.let { status(it) } }
            .build()
}

/**
 * Wraps a real [ChatModel] and counts [stream] calls so a test can assert
 * [com.embabel.agent.spi.LlmService.supportsStreaming] probes once per instance.
 * Keeps the delegate's own stream behaviour: [OpenAiResponsesChatModel] throws locally.
 */
private class ProbeCountingChatModel(
    private val delegate: ChatModel,
) : ChatModel by delegate {
    val streamCalls = AtomicInteger()

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        streamCalls.incrementAndGet()
        return delegate.stream(prompt)
    }
}
