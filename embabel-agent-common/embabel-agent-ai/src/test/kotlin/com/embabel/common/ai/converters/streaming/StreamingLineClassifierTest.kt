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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StreamingLineClassifierTest {

    private fun classify(line: String): List<StreamingEvent<String>> =
        StreamingLineClassifier.classify(line).collectList().block()!!

    @Nested
    inner class PlainText {

        @Test
        fun `plain text emits Thinking with CONTINUATION state`() {
            val events = classify("aaaaaa")
            assertEquals(1, events.size)
            val event = events[0] as StreamingEvent.Thinking
            assertEquals("aaaaaa", event.content)
            assertEquals(ThinkingState.CONTINUATION, event.state)
        }

        @Test
        fun `text with embedded opening tag not at start emits CONTINUATION`() {
            // "aaaaa<think>nnnnn" does not start with <think>, so not detected as START
            val events = classify("aaaaa<think>nnnnn")
            assertEquals(1, events.size)
            val event = events[0] as StreamingEvent.Thinking
            assertEquals("aaaaa<think>nnnnn", event.content)
            assertEquals(ThinkingState.CONTINUATION, event.state)
        }
    }

    @Nested
    inner class ThinkingTags {

        @Test
        fun `complete think block strips tags and emits BOTH`() {
            val events = classify("<think>xyz</think>")
            assertEquals(1, events.size)
            val event = events[0] as StreamingEvent.Thinking
            assertEquals("xyz", event.content)
            assertEquals(ThinkingState.BOTH, event.state)
        }

        @Test
        fun `standalone opening tag emits START`() {
            val events = classify("<think>")
            assertEquals(1, events.size)
            val event = events[0] as StreamingEvent.Thinking
            assertEquals(ThinkingState.START, event.state)
        }

        @Test
        fun `line starting with opening tag and content emits START`() {
            val events = classify("<think>start of reasoning")
            assertEquals(1, events.size)
            val event = events[0] as StreamingEvent.Thinking
            assertEquals(ThinkingState.START, event.state)
        }

        @Test
        fun `standalone closing tag emits END`() {
            val events = classify("</think>")
            assertEquals(1, events.size)
            val event = events[0] as StreamingEvent.Thinking
            assertEquals(ThinkingState.END, event.state)
        }

        @Test
        fun `text ending with closing tag emits END with tags not stripped`() {
            // extractThinkingContent only strips complete <think>…</think> pairs on one line
            val events = classify("nnnnn</think>")
            assertEquals(1, events.size)
            val event = events[0] as StreamingEvent.Thinking
            assertEquals("nnnnn</think>", event.content)
            assertEquals(ThinkingState.END, event.state)
        }
    }

    @Nested
    inner class DroppedLines {

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
            assertTrue(classify("""{"key":"value"}""").isEmpty())
        }
    }
}
