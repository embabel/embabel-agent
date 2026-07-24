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
package com.embabel.agent.spi.support.streaming

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.loop.streaming.LlmStreamChunk
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.agent.core.support.LlmInteraction
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import tools.jackson.module.kotlin.jacksonObjectMapper

class StreamingLlmOperationsThinkingTest {

    @Test
    fun `default streamer extension keeps existing text-only implementations compatible`() {
        val streamer = LlmMessageStreamer { _, _, _ -> Flux.just("one", "two") }

        StepVerifier.create(streamer.streamWithThinking(emptyList(), emptyList(), emptyList()))
            .expectNext(LlmStreamChunk("one"), LlmStreamChunk("two"))
            .verifyComplete()
    }

    @Test
    fun `generate stream with thinking preserves provider event order`() {
        val operations = operations(
            LlmStreamChunk(textContent = "", thinkingContent = listOf("reasoning")),
            LlmStreamChunk(textContent = "answer"),
        )

        StepVerifier.create(
            operations.doTransformStreamWithThinking(
                messages = listOf(UserMessage("question")),
                interaction = interaction(),
                llmRequestEvent = null,
            )
        )
            .assertNext { event ->
                assertThat(event).isEqualTo(StreamingEvent.Thinking("reasoning"))
            }
            .assertNext { event ->
                assertThat(event).isEqualTo(StreamingEvent.Object("answer"))
            }
            .verifyComplete()
    }

    @Test
    fun `object stream interleaves provider thinking before parsed object`() {
        val operations = operations(
            LlmStreamChunk(
                textContent = "{\"value\":\"answer\"}\n",
                thinkingContent = listOf("reasoning"),
            )
        )

        val events = operations.doTransformObjectStreamWithThinking(
            messages = listOf(UserMessage("question")),
            interaction = interaction(),
            outputClass = TestItem::class.java,
            llmRequestEvent = null,
        ).collectList().block()!!

        assertThat(events).containsExactly(
            StreamingEvent.Thinking("reasoning"),
            StreamingEvent.Object(TestItem("answer")),
        )
    }

    @Test
    fun `text stream extracts tagged fallback across chunk boundaries`() {
        val operations = operations(
            LlmStreamChunk(textContent = "<thi"),
            LlmStreamChunk(textContent = "nk>tagged rea"),
            LlmStreamChunk(textContent = "soning</think>final "),
            LlmStreamChunk(textContent = "answer"),
        )

        val events = operations.doTransformStreamWithThinking(
            messages = listOf(UserMessage("question")),
            interaction = interaction(),
            llmRequestEvent = null,
        ).collectList().block()!!

        assertThat(events.filterIsInstance<StreamingEvent.Thinking>().map { it.content })
            .containsExactly("tagged reasoning")
        assertThat(events.filterIsInstance<StreamingEvent.Object<String>>().joinToString("") { it.item })
            .isEqualTo("final answer")
    }

    @Test
    fun `object stream keeps line buffers isolated across subscriptions`() {
        val operations = operations(
            LlmStreamChunk(textContent = "{\"value\":\"first\"}"),
            LlmStreamChunk(textContent = "\n{\"value\":\"second\"}\n"),
        )
        val stream = operations.doTransformObjectStreamWithThinking(
            messages = listOf(UserMessage("question")),
            interaction = interaction(),
            outputClass = TestItem::class.java,
            llmRequestEvent = null,
        )

        val expected = listOf(
            StreamingEvent.Object(TestItem("first")),
            StreamingEvent.Object(TestItem("second")),
        )
        assertThat(stream.collectList().block()).containsExactlyElementsOf(expected)
        assertThat(stream.collectList().block()).containsExactlyElementsOf(expected)
    }

    @Test
    fun `text stream extracts legacy thinking prefix across chunk boundaries`() {
        val operations = operations(
            LlmStreamChunk(textContent = "//THINK"),
            LlmStreamChunk(textContent = "ING: provider rea"),
            LlmStreamChunk(textContent = "soning\nanswer"),
        )

        val events = operations.doTransformStreamWithThinking(
            messages = listOf(UserMessage("question")),
            interaction = interaction(),
            llmRequestEvent = null,
        ).collectList().block()!!

        assertThat(events).containsExactly(
            StreamingEvent.Thinking("provider reasoning"),
            StreamingEvent.Object("answer"),
        )
    }

    @Test
    fun `text stream does not treat a legacy marker in the middle of a line as thinking`() {
        val operations = operations(
            LlmStreamChunk(textContent = "answer //THINK"),
            LlmStreamChunk(textContent = "ING: literal"),
        )

        val events = operations.doTransformStreamWithThinking(
            messages = listOf(UserMessage("question")),
            interaction = interaction(),
            llmRequestEvent = null,
        ).collectList().block()!!

        assertThat(events.filterIsInstance<StreamingEvent.Thinking>()).isEmpty()
        assertThat(events.filterIsInstance<StreamingEvent.Object<String>>().joinToString("") { it.item })
            .isEqualTo("answer //THINKING: literal")
    }

    @Test
    fun `text stream preserves an unclosed thinking tag as text`() {
        val operations = operations(
            LlmStreamChunk(textContent = "before <thi"),
            LlmStreamChunk(textContent = "nk>unfinished reasoning"),
        )

        val events = operations.doTransformStreamWithThinking(
            messages = listOf(UserMessage("question")),
            interaction = interaction(),
            llmRequestEvent = null,
        ).collectList().block()!!

        assertThat(events.filterIsInstance<StreamingEvent.Thinking>()).isEmpty()
        assertThat(events.filterIsInstance<StreamingEvent.Object<String>>().joinToString("") { it.item })
            .isEqualTo("before <think>unfinished reasoning")
    }

    private fun operations(vararg chunks: LlmStreamChunk): StreamingLlmOperationsImpl {
        val streamer = object : LlmMessageStreamer {
            override fun stream(messages: List<com.embabel.chat.Message>, tools: List<com.embabel.agent.api.tool.Tool>, toolCallInspectors: List<com.embabel.agent.api.tool.callback.ToolCallInspector>): Flux<String> =
                Flux.fromArray(chunks).map { it.textContent }.filter { it.isNotEmpty() }

            override fun streamWithThinking(messages: List<com.embabel.chat.Message>, tools: List<com.embabel.agent.api.tool.Tool>, toolCallInspectors: List<com.embabel.agent.api.tool.callback.ToolCallInspector>): Flux<LlmStreamChunk> =
                Flux.fromArray(chunks)
        }
        val llmService = mockk<LlmService<*>> {
            every { promptContributors } returns emptyList()
        }
        return StreamingLlmOperationsImpl(
            messageStreamer = streamer,
            objectMapper = jacksonObjectMapper(),
            llmService = llmService,
            toolDecorator = mockk<ToolDecorator>(),
        )
    }

    private fun interaction() = LlmInteraction(id = InteractionId("thinking-stream"))

    data class TestItem(val value: String)
}
