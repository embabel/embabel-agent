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
package com.embabel.agent.spi.loop.streaming

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.callback.AfterToolCallContext
import com.embabel.agent.api.tool.callback.BeforeToolCallContext
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.spi.loop.ToolInjectionContext
import com.embabel.agent.spi.loop.ToolInjectionResult
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import tools.jackson.module.kotlin.jacksonObjectMapper

class StreamingToolLoopTest {

    @Test
    fun `preserves content across tool turns and executes tools`() {
        val histories = mutableListOf<List<com.embabel.chat.Message>>()
        var calls = 0
        val streamer = inferenceStreamer { messages, _ ->
            histories += messages
            calls++
            if (calls == 1) {
                Flux.just(
                    LlmInferenceStreamEvent.Content("<think>checking</think>"),
                    LlmInferenceStreamEvent.Complete(
                        AssistantMessageWithToolCalls(
                            content = "<think>checking</think>",
                            toolCalls = listOf(ToolCall("call-1", "lookup", "{}")),
                        )
                    ),
                )
            } else {
                Flux.just(
                    LlmInferenceStreamEvent.Content("final"),
                    LlmInferenceStreamEvent.Complete(AssistantMessage("final")),
                )
            }
        }
        val lookup = Tool.of("lookup", "Lookup") { Tool.Result.text("tool-result") }

        val result = DefaultStreamingToolLoop(streamer, jacksonObjectMapper())
            .execute(listOf(UserMessage("question")), listOf(lookup))

        StepVerifier.create(result)
            .expectNext("<think>checking</think>", "final")
            .verifyComplete()
        assertEquals(2, histories.size)
        assertEquals("tool-result", histories[1].filterIsInstance<ToolResultMessage>().single().content)
    }

    @Test
    fun `applies injection strategy before the next turn`() {
        val child = Tool.of("child", "Child tool") { Tool.Result.text("done") }
        val facade = Tool.of("facade", "Reveal child") { Tool.Result.text("revealed") }
        val toolsByTurn = mutableListOf<List<String>>()
        var calls = 0
        val streamer = inferenceStreamer { _, tools ->
            toolsByTurn += tools.map { it.definition.name }
            calls++
            when (calls) {
                1 -> toolCall("facade", "call-1")
                2 -> toolCall("child", "call-2")
                else -> Flux.just(LlmInferenceStreamEvent.Complete(AssistantMessage("complete")))
            }
        }
        val injection = object : ToolInjectionStrategy {
            override fun evaluate(context: ToolInjectionContext): ToolInjectionResult =
                if (context.lastToolCall.toolName == "facade") {
                    ToolInjectionResult.replace(facade, listOf(child))
                } else {
                    ToolInjectionResult.noChange()
                }
        }

        StepVerifier.create(
            DefaultStreamingToolLoop(streamer, jacksonObjectMapper(), injection)
                .execute(listOf(UserMessage("question")), listOf(facade))
        ).verifyComplete()

        assertEquals(listOf("facade"), toolsByTurn[0])
        assertEquals(listOf("child"), toolsByTurn[1])
    }

    @Test
    fun `notifies inspectors from the provider-neutral loop`() {
        val observed = mutableListOf<String>()
        val inspector = object : ToolCallInspector {
            override fun beforeToolCall(context: BeforeToolCallContext) {
                observed += "before:${context.toolCall.id}"
            }

            override fun afterToolCall(context: AfterToolCallContext) {
                observed += "after:${context.toolCall.id}:${context.resultAsString}"
            }
        }
        var calls = 0
        val streamer = inferenceStreamer { _, _ ->
            calls++
            if (calls == 1) toolCall("lookup", "call-1")
            else Flux.just(LlmInferenceStreamEvent.Complete(AssistantMessage("complete")))
        }
        val lookup = Tool.of("lookup", "Lookup") { Tool.Result.text("result") }

        StepVerifier.create(
            DefaultStreamingToolLoop(
                messageStreamer = streamer,
                objectMapper = jacksonObjectMapper(),
                toolCallInspectors = listOf(inspector),
            ).execute(listOf(UserMessage("question")), listOf(lookup))
        ).verifyComplete()

        assertEquals(listOf("before:call-1", "after:call-1:result"), observed)
    }

    @Test
    fun `executes multiple tool calls sequentially in declared order`() {
        val executionOrder = mutableListOf<String>()
        val first = Tool.of("first", "First") {
            executionOrder += "first"
            Tool.Result.text("one")
        }
        val second = Tool.of("second", "Second") {
            executionOrder += "second"
            Tool.Result.text("two")
        }
        var turns = 0
        val streamer = inferenceStreamer { _, _ ->
            turns++
            if (turns == 1) {
                Flux.just(
                    LlmInferenceStreamEvent.Complete(
                        AssistantMessageWithToolCalls(
                            content = "",
                            toolCalls = listOf(
                                ToolCall("call-1", "first", "{}"),
                                ToolCall("call-2", "second", "{}"),
                            ),
                        )
                    )
                )
            } else {
                Flux.just(LlmInferenceStreamEvent.Complete(AssistantMessage("complete")))
            }
        }

        StepVerifier.create(
            DefaultStreamingToolLoop(streamer, jacksonObjectMapper())
                .execute(listOf(UserMessage("question")), listOf(first, second))
        ).verifyComplete()

        assertEquals(listOf("first", "second"), executionOrder)
    }

    @Test
    fun `does not duplicate terminal assistant content`() {
        val streamer = inferenceStreamer { _, _ ->
            Flux.just(
                LlmInferenceStreamEvent.Content("answer"),
                LlmInferenceStreamEvent.Complete(AssistantMessage("answer")),
            )
        }

        StepVerifier.create(
            DefaultStreamingToolLoop(streamer, jacksonObjectMapper())
                .execute(listOf(UserMessage("question")), emptyList())
        )
            .expectNext("answer")
            .verifyComplete()
    }

    private fun toolCall(name: String, id: String): Flux<LlmInferenceStreamEvent> = Flux.just(
        LlmInferenceStreamEvent.Complete(
            AssistantMessageWithToolCalls(
                content = "",
                toolCalls = listOf(ToolCall(id, name, "{}")),
            )
        )
    )

    private fun inferenceStreamer(
        block: (List<Message>, List<Tool>) -> Flux<LlmInferenceStreamEvent>,
    ): LlmMessageStreamer = object : LlmMessageStreamer {
        override fun stream(
            messages: List<Message>,
            tools: List<Tool>,
            toolCallInspectors: List<ToolCallInspector>,
        ): Flux<String> = block(messages, tools)
            .ofType(LlmInferenceStreamEvent.Content::class.java)
            .map { it.text }

        override fun streamInference(
            messages: List<Message>,
            tools: List<Tool>,
        ): Flux<LlmInferenceStreamEvent> = block(messages, tools)
    }
}
