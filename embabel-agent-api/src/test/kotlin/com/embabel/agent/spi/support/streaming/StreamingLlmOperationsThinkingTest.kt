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
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.thinking.ThinkingTags
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import tools.jackson.module.kotlin.jacksonObjectMapper

class StreamingLlmOperationsThinkingTest {

    @Test
    fun `extracts every supported tag at every chunk boundary`() {
        ThinkingTags.TAG_DEFINITIONS
            .filterKeys { it != "legacy_prefix" && it != "no_prefix" }
            .forEach { (name, tags) ->
                val source = "${tags.first}reasoning${tags.second}answer"
                (0..source.length).forEach { split ->
                    val events = parse(source.substring(0, split), source.substring(split))
                    assertThat(events.filterIsInstance<StreamingEvent.Thinking>().map { it.content })
                        .describedAs("$name thinking split at $split")
                        .containsExactly("reasoning")
                    assertThat(events.text())
                        .describedAs("$name text split at $split")
                        .isEqualTo("answer")
                }
            }
    }

    @Test
    fun `extracts legacy prefix at every chunk boundary`() {
        val source = "//THINKING: reasoning\nanswer"

        (0..source.length).forEach { split ->
            val events = parse(source.substring(0, split), source.substring(split))
            assertThat(events.filterIsInstance<StreamingEvent.Thinking>().map { it.content })
                .describedAs("legacy thinking split at $split")
                .containsExactly("reasoning")
            assertThat(events.text())
                .describedAs("legacy text split at $split")
                .isEqualTo("answer")
        }
    }

    @Test
    fun `extracts tagged thinking across chunk boundaries`() {
        val events = collect("<thi", "nk>tagged rea", "soning</think>final ", "answer")

        assertThat(events.filterIsInstance<StreamingEvent.Thinking>().map { it.content })
            .containsExactly("tagged reasoning")
        assertThat(events.text()).isEqualTo("final answer")
    }

    @Test
    fun `extracts legacy thinking prefix across chunk boundaries`() {
        val events = collect("//THINK", "ING: legacy rea", "soning\nanswer")

        assertThat(events).containsExactly(
            StreamingEvent.Thinking("legacy reasoning"),
            StreamingEvent.Object("answer"),
        )
    }

    @Test
    fun `keeps a legacy marker in the middle of a line as text`() {
        val events = collect("answer //THINK", "ING: literal")

        assertThat(events.filterIsInstance<StreamingEvent.Thinking>()).isEmpty()
        assertThat(events.text()).isEqualTo("answer //THINKING: literal")
    }

    @Test
    fun `keeps an unclosed thinking tag as text`() {
        val events = collect("before <thi", "nk>unfinished reasoning")

        assertThat(events.filterIsInstance<StreamingEvent.Thinking>()).isEmpty()
        assertThat(events.text()).isEqualTo("before <think>unfinished reasoning")
    }

    @Test
    fun `keeps parser state isolated for repeated subscriptions`() {
        val stream = operations("<think>reasoning</think>answer")
            .doTransformStreamWithThinking(
                messages = listOf(UserMessage("question")),
                interaction = interaction(),
                llmRequestEvent = null,
            )
        val expected = listOf(
            StreamingEvent.Thinking("reasoning"),
            StreamingEvent.Object("answer"),
        )

        assertThat(stream.collectList().block()).containsExactlyElementsOf(expected)
        assertThat(stream.collectList().block()).containsExactlyElementsOf(expected)
    }

    private fun collect(vararg chunks: String): List<StreamingEvent<String>> =
        operations(*chunks)
            .doTransformStreamWithThinking(
                messages = listOf(UserMessage("question")),
                interaction = interaction(),
                llmRequestEvent = null,
            )
            .collectList()
            .block()!!

    private fun parse(vararg chunks: String): List<StreamingEvent<String>> =
        Flux.fromArray(chunks).toTaggedThinkingEvents().collectList().block()!!

    private fun operations(vararg chunks: String): StreamingLlmOperationsImpl {
        val llmService = mockk<LlmService<*>> {
            every { promptContributors } returns emptyList()
        }
        return StreamingLlmOperationsImpl(
            messageStreamer = LlmMessageStreamer { _, _, _ -> Flux.fromArray(chunks) },
            objectMapper = jacksonObjectMapper(),
            llmService = llmService,
            toolDecorator = mockk<ToolDecorator>(),
        )
    }

    private fun interaction() = LlmInteraction(id = InteractionId("thinking-stream"))

    private fun List<StreamingEvent<String>>.text(): String =
        filterIsInstance<StreamingEvent.Object<String>>().joinToString("") { it.item }
}
