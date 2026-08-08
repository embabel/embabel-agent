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
package com.embabel.common.ai.converters.streaming

import com.embabel.common.core.streaming.StreamingEvent
import com.embabel.common.core.streaming.ThinkingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamingLineClassifierTest {

    private fun classify(line: String): List<StreamingEvent<String>> =
        StreamingLineClassifier.classify(line).collectList().block()!!

    @Test
    fun `plain text emits Thinking with CONTINUATION state`() {
        val events = classify("plain reasoning text")
        assertEquals(1, events.size)
        val event = events.single() as StreamingEvent.Thinking
        assertEquals("plain reasoning text", event.content)
        assertEquals(ThinkingState.CONTINUATION, event.state)
    }

    @Test
    fun `text with embedded opening tag not at start emits CONTINUATION`() {
        val events = classify("prefix <think>reasoning")
        assertEquals(ThinkingState.CONTINUATION, (events.single() as StreamingEvent.Thinking).state)
    }

    @Test
    fun `complete think block strips tags and emits BOTH`() {
        val event = classify("<think>reasoning</think>").single() as StreamingEvent.Thinking
        assertEquals("reasoning", event.content)
        assertEquals(ThinkingState.BOTH, event.state)
    }

    @Test
    fun `standalone opening tag emits START`() {
        assertEquals(ThinkingState.START, (classify("<think>").single() as StreamingEvent.Thinking).state)
    }

    @Test
    fun `line starting with opening tag and content emits START`() {
        assertEquals(ThinkingState.START, (classify("<think>reasoning").single() as StreamingEvent.Thinking).state)
    }

    @Test
    fun `standalone closing tag emits END`() {
        assertEquals(ThinkingState.END, (classify("</think>").single() as StreamingEvent.Thinking).state)
    }

    @Test
    fun `text ending with closing tag emits END with tags not stripped`() {
        val event = classify("reasoning</think>").single() as StreamingEvent.Thinking
        assertEquals("reasoning</think>", event.content)
        assertEquals(ThinkingState.END, event.state)
    }

    @Test
    fun `code fence backtick-json is dropped`() {
        assertTrue(classify("```json").isEmpty())
    }

    @Test
    fun `bare code fence is dropped`() {
        assertTrue(classify("```").isEmpty())
    }

    @Test
    fun `JSON-shaped line is dropped`() {
        assertTrue(classify("{\"key\":\"value\"}").isEmpty())
    }

    @Test
    fun `JSON string line is dropped`() {
        assertTrue(classify("\"final answer\"").isEmpty())
    }
}
