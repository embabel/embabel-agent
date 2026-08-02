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
import com.embabel.agent.core.internal.streaming.toThinkingEvents
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.streaming.ThinkingState
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import tools.jackson.module.kotlin.jacksonObjectMapper

class StreamingLlmOperationsThinkingTest {

    @Test
    fun `reassembles chunked lines before classifying them`() {
        val events = collect("<th", "ink", ">\n", "reasoning\n", "</think>\n")

        assertThat(events.map { it.state }).containsExactly(
            ThinkingState.START,
            ThinkingState.CONTINUATION,
            ThinkingState.END,
        )
    }

    @Test
    fun `emits plain text lines only as thinking events`() {
        val events = collect("line one\nline two\n")

        assertThat(events).allSatisfy { event ->
            assertThat(event).isInstanceOf(StreamingEvent.Thinking::class.java)
            assertThat(event.state).isEqualTo(ThinkingState.CONTINUATION)
        }
        assertThat(events.map { it.content }).containsExactly("line one", "line two")
    }

    @Test
    fun `strips a complete thinking tag`() {
        assertThat(collect("<think>reasoning</think>\n")).containsExactly(
            StreamingEvent.Thinking("reasoning", ThinkingState.BOTH),
        )
    }

    @Test
    fun `drops JSON and markdown fences`() {
        val events = collect("```json\n{\"answer\":\"value\"}\n```\n")

        assertThat(events).isEmpty()
    }

    @Test
    fun `flushes a final line without a newline`() {
        assertThat(collect("hello ", "world")).containsExactly(
            StreamingEvent.Thinking("hello world", ThinkingState.CONTINUATION),
        )
    }

    @Test
    fun `keeps final-line buffering isolated between subscriptions`() {
        val stream = operations("hello ", "world")
            .generateStreamWithThinking(
                messages = listOf(UserMessage("question")),
                interaction = interaction(),
                agentProcess = mockk<AgentProcess>(relaxed = true),
                action = null,
            )

        assertThat(stream.collectList().block()).containsExactly(
            StreamingEvent.Thinking("hello world", ThinkingState.CONTINUATION),
        )
        assertThat(stream.collectList().block()).containsExactly(
            StreamingEvent.Thinking("hello world", ThinkingState.CONTINUATION),
        )
    }

    @Test
    fun `createObjectStream supports String output`() {
        val result = operations("\"final answer\"\n")
            .createObjectStream(
                messages = listOf(UserMessage("question")),
                interaction = interaction(),
                outputClass = String::class.java,
                agentProcess = mockk(relaxed = true),
                action = null,
            )
            .collectList()
            .block()!!

        assertThat(result).containsExactly("final answer")
    }

    @Test
    fun `reports overhead for a large chunked stream`() {
        val chunks = ("x".repeat(50_000) + "\n").chunked(50)

        repeat(PERFORMANCE_WARMUPS) {
            consumeRaw(chunks)
            consumeThinking(chunks)
        }
        val baselineNanos = averageNanos(PERFORMANCE_RUNS) { consumeRaw(chunks) }
        val classifiedNanos = averageNanos(PERFORMANCE_RUNS) { consumeThinking(chunks) }
        val overheadNanos = classifiedNanos - baselineNanos

        println(
            "Thinking line classification performance: input=50000 chars, " +
                "chunks=${chunks.size}, runs=$PERFORMANCE_RUNS, " +
                "baseline=${baselineNanos.toMillis()} ms, " +
                "classified=${classifiedNanos.toMillis()} ms, " +
                "overhead=${overheadNanos.toMillis()} ms",
        )
        val event = consumeThinking(chunks).single() as StreamingEvent.Thinking
        assertThat(event.content).hasSize(50_000)
    }

    private fun collect(vararg chunks: String): List<StreamingEvent.Thinking> =
        operations(*chunks)
            .generateStreamWithThinking(
                messages = listOf(UserMessage("question")),
                interaction = interaction(),
                agentProcess = mockk<AgentProcess>(relaxed = true),
                action = null,
            )
            .map { it as StreamingEvent.Thinking }
            .collectList()
            .block()!!

    private fun consumeRaw(chunks: List<String>): List<String> =
        Flux.fromIterable(chunks).collectList().block()!!

    private fun consumeThinking(chunks: List<String>): List<StreamingEvent<String>> =
        Flux.fromIterable(chunks).toThinkingEvents().collectList().block()!!

    private fun interaction() = LlmInteraction(id = InteractionId("thinking-stream"))

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

    private fun averageNanos(runs: Int, action: () -> Unit): Long {
        var total = 0L
        repeat(runs) {
            val started = System.nanoTime()
            action()
            total += System.nanoTime() - started
        }
        return total / runs
    }

    private fun Long.toMillis(): String = "%.3f".format(this / 1_000_000.0)

    private companion object {
        const val PERFORMANCE_WARMUPS = 10
        const val PERFORMANCE_RUNS = 30
    }
}
