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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.support.Dog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.converter.BeanOutputConverter

class SuppressThinkingConverterTest {

    @Nested
    inner class WithoutThinkingBlock {

        @Test
        fun `works with no thinking`() {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = """{"name": "Rex"}"""
            val result = converter.convert(input)
            assertNotNull(result!!)
            assertEquals("Rex", result.name)
        }
    }

    @Nested
    inner class WithThinkingBlocks {

        fun checkThinkContent(thinkContent: String) {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = """$thinkContent
                {"name": "Rex"}""".trimMargin()
            val result = converter.convert(input)
            assertNotNull(result!!)
            assertEquals("Rex", result.name)
        }

        @Test
        fun `with think markup block`() {
            checkThinkContent("<think>I am thinking</think>")
        }

        @Test
        fun `with preface think blog`() {
            checkThinkContent("I am thinking")
        }
    }

    @Nested
    inner class FencedJson {

        /**
         * The shape that motivated [FindSuffixThinkBlock]: a model that explains
         * itself, then emits the object inside a markdown fence. The prefix finder
         * already handled everything to the left of `{`; the closing fence was what
         * killed it.
         */
        @Test
        fun `object wrapped in a markdown fence, with a preamble`() {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = """
                Based on my research, I found one match.

                ```json
                {"name": "Rex"}
                ```
            """.trimIndent()
            assertEquals("Rex", converter.convert(input)!!.name)
        }

        @Test
        fun `fence with no language tag`() {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = "```\n{\"name\": \"Rex\"}\n```"
            assertEquals("Rex", converter.convert(input)!!.name)
        }

        @Test
        fun `prose after the object, with no fence at all`() {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = "{\"name\": \"Rex\"}\n\nLet me know if you need anything else."
            assertEquals("Rex", converter.convert(input)!!.name)
        }

        @Test
        fun `a multi-line object is kept whole - the anchor is the LAST brace`() {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = """
                ```json
                {
                  "name": "Rex"
                }
                ```
            """.trimIndent()
            assertEquals("Rex", converter.convert(input)!!.name)
        }

        @Test
        fun `think tag, preamble, fence and trailing prose together`() {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = """
                <think>weighing the options</think>
                Here is the answer.
                ```json
                {"name": "Rex"}
                ```
                Hope that helps.
            """.trimIndent()
            assertEquals("Rex", converter.convert(input)!!.name)
        }

        @Test
        fun `input with no object at all is left alone for the parser to report`() {
            // Deleting everything would replace a diagnosable parse error with an
            // empty string, which is strictly harder to debug.
            assertEquals(null, FindSuffixThinkBlock("I could not find anything relevant."))
        }

        @Test
        fun `an already-clean object is untouched`() {
            assertEquals("", FindSuffixThinkBlock("""{"name": "Rex"}"""))
        }
    }

    @Nested
    inner class SequentialProcessing {

        @Test
        fun `applies all finders sequentially - TAG then PREFIX`() {
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = """<think>First thinking block</think>
                //THINKING: Second thinking block
                {"name": "Rex"}""".trimMargin()
            val result = converter.convert(input)
            assertNotNull(result!!)
            assertEquals("Rex", result.name)
        }

        @Test
        fun `early termination when JSON is already valid`() {
            // If the input is already valid JSON, no sanitization should occur
            val converter = SuppressThinkingConverter(BeanOutputConverter(Dog::class.java))
            val input = """{"name": "Rex"}"""
            val result = converter.convert(input)
            assertNotNull(result!!)
            assertEquals("Rex", result.name)
        }
    }

    @Nested
    inner class StringWithoutThinkBlocks {

        fun checkStringThinkContent(thinkContent: String) {
            val input = """$thinkContent
                You are a dog""".trimMargin()
            val result = stringWithoutThinkBlocks(input)
            assertNotNull(result)
            assertEquals("You are a dog", result.trim())
        }

        @Test
        fun `with think markup block`() {
            checkStringThinkContent("<think>I am thinking</think>")
        }

        @Test
        fun `simple string without think block`() {
            val input = "fake response"
            val result = stringWithoutThinkBlocks(input)
            assertNotNull(result)
            assertEquals("fake response", result.trim())
        }
    }

}
