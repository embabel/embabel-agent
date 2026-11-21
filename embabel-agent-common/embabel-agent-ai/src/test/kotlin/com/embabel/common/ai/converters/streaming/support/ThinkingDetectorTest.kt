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
package com.embabel.common.ai.converters.streaming.support

import com.embabel.common.core.streaming.ThinkingState
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class ThinkingDetectorTest {

    private val isValidJsonMethod: Method by lazy {
        ThinkingDetector::class.java.getDeclaredMethod("isValidJson", String::class.java).apply {
            isAccessible = true
        }
    }

    private fun invokeIsValidJson(line: String): Boolean {
        return isValidJsonMethod.invoke(ThinkingDetector, line) as Boolean
    }

    @Test
    fun `isThinkingLine should detect standard thinking tags`() {
        assertTrue(ThinkingDetector.isThinkingLine("<think>content</think>"))
        assertTrue(ThinkingDetector.isThinkingLine("<analysis>content</analysis>"))
        assertTrue(ThinkingDetector.isThinkingLine("<thought>content</thought>"))
        assertTrue(ThinkingDetector.isThinkingLine("<final>content</final>"))
        assertTrue(ThinkingDetector.isThinkingLine("<scratchpad>content</scratchpad>"))
        assertTrue(ThinkingDetector.isThinkingLine("<chain_of_thought>content</chain_of_thought>"))
        assertTrue(ThinkingDetector.isThinkingLine("<reasoning>content</reasoning>"))
        assertTrue(ThinkingDetector.isThinkingLine("//THINKING: content"))
    }

    @Test
    fun `isThinkingLine should not detect JSON or regular text`() {
        assertFalse(ThinkingDetector.isThinkingLine("{\"name\": \"value\"}"))
        assertFalse(ThinkingDetector.isThinkingLine("regular text line"))
        assertFalse(ThinkingDetector.isThinkingLine(""))
        assertFalse(ThinkingDetector.isThinkingLine("random <tag> not thinking"))
    }

    @Test
    fun `extractThinkingContent should extract content from thinking tags`() {
        Assertions.assertEquals("content", ThinkingDetector.extractThinkingContent("<think>content</think>"))
        Assertions.assertEquals(
            "analysis content",
            ThinkingDetector.extractThinkingContent("<analysis>analysis content</analysis>")
        )
        Assertions.assertEquals(
            "thought content",
            ThinkingDetector.extractThinkingContent("<thought>thought content</thought>")
        )
        Assertions.assertEquals(
            "xml reasoning",
            ThinkingDetector.extractThinkingContent("<reasoning>xml reasoning</reasoning>")
        )
        Assertions.assertEquals("legacy thinking", ThinkingDetector.extractThinkingContent("//THINKING: legacy thinking"))
    }

    @Test
    fun `extractThinkingContent should handle multiline content`() {
        val multilineContent = "<think>line 1\nline 2\nline 3</think>"
        Assertions.assertEquals("line 1\nline 2\nline 3", ThinkingDetector.extractThinkingContent(multilineContent))
    }

    @Test
    fun `extractThinkingContent should trim whitespace`() {
        Assertions.assertEquals("content", ThinkingDetector.extractThinkingContent("<think>  content  </think>"))
        Assertions.assertEquals("thinking", ThinkingDetector.extractThinkingContent("//THINKING:   thinking   "))
    }

    @Test
    fun `extractThinkingContent should return original line if no pattern matches`() {
        val nonThinkingLine = "regular text"
        Assertions.assertEquals("regular text", ThinkingDetector.extractThinkingContent(nonThinkingLine))
    }

    @Test
    fun `detectThinkingState should return BOTH for complete single-line blocks`() {
        Assertions.assertEquals(
            ThinkingState.BOTH,
            ThinkingDetector.detectThinkingState("<think>complete thought</think>")
        )
        Assertions.assertEquals(
            ThinkingState.BOTH,
            ThinkingDetector.detectThinkingState("<analysis>complete analysis</analysis>")
        )
        Assertions.assertEquals(
            ThinkingState.BOTH,
            ThinkingDetector.detectThinkingState("<reasoning>complete reasoning</reasoning>")
        )
        Assertions.assertEquals(
            ThinkingState.BOTH,
            ThinkingDetector.detectThinkingState("//THINKING: complete legacy thought")
        )
    }

    @Test
    fun `detectThinkingState should return START for opening tags only`() {
        Assertions.assertEquals(ThinkingState.START, ThinkingDetector.detectThinkingState("<think>"))
        Assertions.assertEquals(ThinkingState.START, ThinkingDetector.detectThinkingState("<analysis>"))
        Assertions.assertEquals(ThinkingState.START, ThinkingDetector.detectThinkingState("<reasoning>"))
    }

    @Test
    fun `detectThinkingState should return END for closing tags only`() {
        Assertions.assertEquals(ThinkingState.END, ThinkingDetector.detectThinkingState("</think>"))
        Assertions.assertEquals(ThinkingState.END, ThinkingDetector.detectThinkingState("</analysis>"))
        Assertions.assertEquals(ThinkingState.END, ThinkingDetector.detectThinkingState("</reasoning>"))
    }

    @Test
    fun `detectThinkingState should return START for opening tag with content but no closing`() {
        Assertions.assertEquals(ThinkingState.START, ThinkingDetector.detectThinkingState("<think>starting to think"))
        Assertions.assertEquals(ThinkingState.START, ThinkingDetector.detectThinkingState("<analysis>beginning analysis"))
    }

    @Test
    fun `detectThinkingState should return END for content with closing tag`() {
        Assertions.assertEquals(ThinkingState.END, ThinkingDetector.detectThinkingState("ending thought</think>"))
        Assertions.assertEquals(ThinkingState.END, ThinkingDetector.detectThinkingState("final analysis</analysis>"))
    }

    @Test
    fun `detectThinkingState should return NONE for valid JSON`() {
        Assertions.assertEquals(ThinkingState.NONE, ThinkingDetector.detectThinkingState("{\"name\": \"value\"}"))
        Assertions.assertEquals(ThinkingState.NONE, ThinkingDetector.detectThinkingState("{\"field\": 123}"))
        Assertions.assertEquals(
            ThinkingState.NONE,
            ThinkingDetector.detectThinkingState("{\"complex\": {\"nested\": true}}")
        )
    }

    @Test
    fun `detectThinkingState should return CONTINUATION for non-JSON text`() {
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("regular text line"))
        Assertions.assertEquals(
            ThinkingState.CONTINUATION,
            ThinkingDetector.detectThinkingState("continuing the thought")
        )
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("invalid json { broken"))
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("partial json }"))
    }

    @Test
    fun `isValidJson should identify valid JSON objects`() {
        assertTrue(invokeIsValidJson("{\"name\": \"value\"}"))
        assertTrue(invokeIsValidJson("{\"field\": 123, \"other\": true}"))
        assertTrue(invokeIsValidJson("  {\"trimmed\": true}  "))
    }

    @Test
    fun `isValidJson should reject invalid JSON formats`() {
        assertFalse(invokeIsValidJson("not json"))
        assertFalse(invokeIsValidJson("[\"array\", \"not\", \"object\"]"))
        assertFalse(invokeIsValidJson("{\"incomplete\": "))
        assertFalse(invokeIsValidJson("incomplete\": \"value\"}"))
        assertFalse(invokeIsValidJson("\"just a string\""))
        assertFalse(invokeIsValidJson("123"))
        assertFalse(invokeIsValidJson("true"))
        assertFalse(invokeIsValidJson(""))
        assertFalse(invokeIsValidJson("   "))
        assertFalse(invokeIsValidJson("{}"))  // No colon = not valid JSON object
    }

    @Test
    fun `isValidJson should be fast heuristic, not perfect parser`() {
        // This test documents that isValidJson() is a fast heuristic, not perfect
        // It may have false positives, but actual JSON parsing will catch real errors
        assertTrue(invokeIsValidJson("{malformed: but has colon}"))
        assertTrue(invokeIsValidJson("{\"unclosed\": \"string}"))

        // But should reject obvious non-JSON
        assertFalse(invokeIsValidJson("{no colon here}"))
        assertFalse(invokeIsValidJson("{malformed but looks like object}"))
    }

    @Test
    fun `detectThinkingState should handle complex multi-line scenarios`() {
        // Multi-line block start
        Assertions.assertEquals(ThinkingState.START, ThinkingDetector.detectThinkingState("<think>"))
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("This is line 1"))
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("This is line 2"))
        Assertions.assertEquals(ThinkingState.END, ThinkingDetector.detectThinkingState("</think>"))

        // Mixed content
        Assertions.assertEquals(ThinkingState.NONE, ThinkingDetector.detectThinkingState("{\"after\": \"thinking\"}"))
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("some more thoughts"))
    }

    @Test
    fun `detectThinkingState should handle edge cases`() {
        // Empty lines
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState(""))
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("   "))

        // Lines with only partial JSON
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("{"))
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("}"))
        Assertions.assertEquals(ThinkingState.CONTINUATION, ThinkingDetector.detectThinkingState("{\"incomplete"))
    }

    /**
     * A JSON object directly after a closing thinking tag is split off (issue #2267).
     * Without a closing tag the line stays whole: the detector is stateless (see #1881).
     */
    @Nested
    inner class SplitAfterClosingTag {

        @Test
        fun `splits a think block followed by JSON`() {
            assertEquals(
                "<think>r</think>" to """{"name":"x"}""",
                ThinkingDetector.splitAfterClosingTag("""<think>r</think>{"name":"x"}"""),
            )
        }

        @Test
        fun `splits a think block followed by a space and JSON`() {
            assertEquals(
                "<think>r</think>" to """{"name":"x"}""",
                ThinkingDetector.splitAfterClosingTag("""  <think>r</think> {"name":"x"}  """),
            )
        }

        @Test
        fun `splits the end of a multi-line block followed by JSON`() {
            assertEquals(
                "last step</think>" to """{"name":"x"}""",
                ThinkingDetector.splitAfterClosingTag("""last step</think>{"name":"x"}"""),
            )
        }

        @Test
        fun `splits after a closing tag of another tag family`() {
            assertEquals(
                "<analysis>r</analysis>" to """{"name":"x"}""",
                ThinkingDetector.splitAfterClosingTag("""<analysis>r</analysis>{"name":"x"}"""),
            )
        }

        @Test
        fun `splits after the last of several closing tags`() {
            assertEquals(
                "<think>a</think><analysis>b</analysis>" to """{"name":"x"}""",
                ThinkingDetector.splitAfterClosingTag("""<think>a</think><analysis>b</analysis>{"name":"x"}"""),
            )
        }

        @Test
        fun `does not split plain text followed by JSON`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""Consider {"name":"example"}"""))
        }

        @Test
        fun `does not split a plain JSON line`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""{"name":"x"}"""))
        }

        @Test
        fun `does not split a line that does not end with a brace`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""<think>r</think>{"name":"x"} trailing"""))
        }

        @Test
        fun `does not split JSON inside a block opened on the same line`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""<think>example {"name":"x"}"""))
        }

        @Test
        fun `does not split JSON inside a block opened mid-line`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""so <think>example {"name":"x"}"""))
        }

        @Test
        fun `does not split JSON inside a block opened after a closing tag`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""<think>a</think> so <think>b {"name":"x"}"""))
        }

        @Test
        fun `does not split when the last closing tag is inside the JSON`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""<think>r</think>{"name":"<think>a</think>"}"""))
        }

        @Test
        fun `does not split text after the closing tag that is not JSON-shaped`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""<think>r</think>{a, b}"""))
        }

        @Test
        fun `does not split a legacy THINKING prefix line`() {
            assertNull(ThinkingDetector.splitAfterClosingTag("""//THINKING: text {"name":"x"}"""))
        }
    }
}
