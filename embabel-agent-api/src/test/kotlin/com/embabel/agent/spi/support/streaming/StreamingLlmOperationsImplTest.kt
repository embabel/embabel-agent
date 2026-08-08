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
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class StreamingLlmOperationsImplTest {

    private lateinit var llmService: LlmService<*>
    private lateinit var toolDecorator: ToolDecorator
    private lateinit var agentProcess: AgentProcess

    private val interaction = LlmInteraction(id = InteractionId("test"))
    private val messages = listOf(UserMessage("hello"))

    @BeforeEach
    fun setUp() {
        llmService = mockk(relaxed = true)
        toolDecorator = mockk(relaxed = true)
        agentProcess = mockk(relaxed = true)
        every { llmService.promptContributors } returns emptyList()
    }

    private fun implWith(vararg chunks: String): StreamingLlmOperationsImpl {
        val streamer = LlmMessageStreamer { _, _, _ -> Flux.fromArray(chunks) }
        return StreamingLlmOperationsImpl(
            messageStreamer = streamer,
            objectMapper = jacksonObjectMapper(),
            llmService = llmService,
            toolDecorator = toolDecorator,
        )
    }

    private fun run(impl: StreamingLlmOperationsImpl): List<StreamingEvent<String>> =
        impl.generateStreamWithThinking(messages, interaction, agentProcess, null)
            .collectList().block()!!

    @Nested
    inner class GenerateStreamWithThinking {

        @Nested
        inner class LineBuffering {

            @Test
            fun `chunks split across emissions are reassembled before classification`() {
                // "<think>\n" arrives as three separate chunks, none of which is a complete line alone
                val events = run(implWith("<th", "ink", ">\n", "reasoning\n", "</think>\n"))
                assertEquals(3, events.size)
                assertEquals(ThinkingState.START, (events[0] as StreamingEvent.Thinking).state)
                assertEquals(ThinkingState.CONTINUATION, (events[1] as StreamingEvent.Thinking).state)
                assertEquals(ThinkingState.END, (events[2] as StreamingEvent.Thinking).state)
            }

            @Test
            fun `multiple newlines in one chunk emit multiple events`() {
                val events = run(implWith("line one\nline two\n"))
                assertEquals(2, events.size)
            }

            @Test
            fun `trailing content without newline is flushed at stream end`() {
                // no trailing \n — rawChunksToLines flushes the buffer on complete
                val events = run(implWith("hello world"))
                assertEquals(1, events.size)
                assertEquals("hello world", (events[0] as StreamingEvent.Thinking).content)
            }
        }

        @Nested
        inner class ThinkingClassification {

            @Test
            fun `complete think block emits BOTH with tags stripped`() {
                val events = run(implWith("<think>reasoning</think>\n"))
                assertEquals(1, events.size)
                val event = events[0] as StreamingEvent.Thinking
                assertEquals("reasoning", event.content)
                assertEquals(ThinkingState.BOTH, event.state)
            }

            @Test
            fun `multi-line think block emits START then CONTINUATION then END`() {
                val events = run(implWith("<think>\n", "mid\n", "</think>\n"))
                assertEquals(3, events.size)
                assertEquals(ThinkingState.START, (events[0] as StreamingEvent.Thinking).state)
                assertEquals(ThinkingState.CONTINUATION, (events[1] as StreamingEvent.Thinking).state)
                assertEquals(ThinkingState.END, (events[2] as StreamingEvent.Thinking).state)
            }

            @Test
            fun `plain prose lines emit as CONTINUATION`() {
                val events = run(implWith("line one\n", "line two\n"))
                assertEquals(2, events.size)
                events.forEach { assertEquals(ThinkingState.CONTINUATION, (it as StreamingEvent.Thinking).state) }
            }

            @Test
            fun `JSON-shaped lines are dropped`() {
                val events = run(implWith("{\"key\":\"value\"}\n"))
                assertTrue(events.isEmpty())
            }

            @Test
            fun `code fence lines are dropped`() {
                val events = run(implWith("```json\n", "<think>reasoning</think>\n", "```\n"))
                assertEquals(1, events.size)
                assertEquals(ThinkingState.BOTH, (events[0] as StreamingEvent.Thinking).state)
            }
        }
    }
}
