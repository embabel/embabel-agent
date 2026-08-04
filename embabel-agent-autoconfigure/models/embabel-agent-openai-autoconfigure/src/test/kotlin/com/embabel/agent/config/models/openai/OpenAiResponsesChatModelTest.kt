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

import com.openai.client.OpenAIClient
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputText
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ResponseUsage
import com.openai.services.blocking.ResponseService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import java.util.Optional

/**
 * The adapter is the only thing standing between Embabel and a wire format Spring AI cannot
 * speak, so these tests pin the contract in both directions: what Embabel guarantees to the
 * adapter, and what the surrounding code assumes of what comes back.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 */
class OpenAiResponsesChatModelTest {

    private val client = mockk<OpenAIClient>()
    private val responseService = mockk<ResponseService>()

    private val model = OpenAiResponsesChatModel(
        client = client,
        defaultOptions = OpenAiChatOptions.builder().model("gpt-5-pro").build(),
    )

    /** Stubs the SDK call and returns the params the adapter built. */
    private fun capture(prompt: Prompt, response: Response = textResponse("ok")): ResponseCreateParams {
        val params = slot<ResponseCreateParams>()
        every { client.responses() } returns responseService
        every { responseService.create(capture(params)) } returns response
        model.call(prompt)
        return params.captured
    }

    private fun respondWith(response: Response) {
        every { client.responses() } returns responseService
        every { responseService.create(any<ResponseCreateParams>()) } returns response
    }

    @Nested
    inner class RequestMapping {

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

    @Nested
    inner class ContractWithSurroundingCode {

        /**
         * `StreamingCapabilityVerifier` probes streaming support by calling [stream] and catching
         * exactly [UnsupportedOperationException]; any other type propagates and takes down the
         * caller instead of reporting "no streaming here".
         */
        @Test
        fun `stream signals unsupported in the one way the verifier understands`() {
            assertThrows(UnsupportedOperationException::class.java) {
                model.stream(Prompt("Hi"))
            }
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

    private fun toolCallback(name: String, description: String) =
        object : org.springframework.ai.tool.ToolCallback {
            override fun getToolDefinition(): ToolDefinition =
                ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema("""{"type":"object","properties":{"q":{"type":"string"}}}""")
                    .build()

            override fun call(toolInput: String): String = "unused"
        }

    private fun usage(inputTokens: Long, outputTokens: Long): ResponseUsage =
        ResponseUsage.builder()
            .inputTokens(inputTokens)
            .inputTokensDetails(ResponseUsage.InputTokensDetails.builder().cachedTokens(0).build())
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

    private fun response(vararg output: ResponseOutputItem, usage: ResponseUsage? = null): Response =
        Response.builder()
            .id("resp_1")
            .createdAt(0.0)
            .model("gpt-5-pro")
            .output(output.toList())
            .parallelToolCalls(false)
            .toolChoice(com.openai.models.responses.ToolChoiceOptions.AUTO)
            .tools(emptyList())
            // The SDK treats these as required even when absent on the wire.
            .error(Optional.empty())
            .incompleteDetails(Optional.empty())
            .instructions(Optional.empty())
            .metadata(Optional.empty())
            .temperature(Optional.empty())
            .topP(Optional.empty())
            .apply { usage?.let { usage(it) } }
            .build()
}
