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
import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.embabel.common.ai.converters.JacksonOutputConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.converter.BeanOutputConverter
import tools.jackson.module.kotlin.jacksonObjectMapper

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
        fun `identical repeated blocks containing braces do not hide the object`() {
            val converter = SuppressThinkingConverter(JacksonOutputConverter(Dog::class.java, jacksonObjectMapper()))
            val result = converter.convert("""<think>x } y</think>{"name": "Rex"}<think>x } y</think>""")
            // Expected: both copies of the block are removed, leaving {"name": "Rex"}
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

    /**
     * The text removed around the object also appears inside a JSON value.
     * Removing it everywhere damaged the value; it must be removed only outside the object.
     */
    @Nested
    inner class ValuesContainingTheRemovedText {

        private val converter = SuppressThinkingConverter(JacksonOutputConverter(Dog::class.java, jacksonObjectMapper()))

        @Test
        fun `a space after the think block is not removed from values`() {
            val result = converter.convert("""<think>I am thinking</think> {"name": "Sir Rex"}""")
            // Expected: "Sir Rex". Before this change the space was removed everywhere, giving "SirRex"
            assertEquals("Sir Rex", result.name)
        }

        @Test
        fun `text after the object is not removed from values`() {
            val result = converter.convert("""{"name": "Rex Jr."}.""")
            // Expected: "Rex Jr.". Before this change the "." was removed everywhere, giving "Rex Jr"
            assertEquals("Rex Jr.", result.name)
        }

        @Test
        fun `text before the object is not removed from values`() {
            val result = converter.convert("""Sure! {"name": "Sure! Rex"}""")
            // Expected: "Sure! Rex". Before this change "Sure! " was removed everywhere, giving "Rex"
            assertEquals("Sure! Rex", result.name)
        }

        @Test
        fun `a period before the object is not removed from values`() {
            val result = converter.convert(""".{"name": "Rex Jr."}""")
            // Expected: "Rex Jr.". Before this change the "." was removed everywhere, giving "Rex Jr"
            assertEquals("Rex Jr.", result.name)
        }

        @Test
        fun `text on both sides of the object is not removed from values`() {
            val result = converter.convert("""<think>I am thinking</think> {"name": "Sir Rex"}.""")
            // Expected: "Sir Rex". Before this change the space was removed everywhere, giving "SirRex"
            assertEquals("Sir Rex", result.name)
        }

        @Test
        fun `a filtering converter keeps the space in values`() {
            val filtering = SuppressThinkingConverter(
                FilteringJacksonOutputConverter<Dog>(
                    clazz = Dog::class.java,
                    objectMapper = jacksonObjectMapper(),
                    fieldFilter = { true },
                ),
            )
            val result = filtering.convert("""<think>I am thinking</think> {"name": "Sir Rex"}""")
            // Expected: "Sir Rex", the same as with JacksonOutputConverter
            assertEquals("Sir Rex", result.name)
        }
    }

    // The delegate that `ChatClientLlmOperations` wraps
    private fun filteringConverter() = SuppressThinkingConverter(
        FilteringJacksonOutputConverter<Dog>(
            clazz = Dog::class.java,
            objectMapper = jacksonObjectMapper(),
            fieldFilter = { true },
        ),
    )

    /**
     * Positive cases: the object is found and its value comes back exactly as the model wrote it.
     */
    @Nested
    inner class PositiveCases {

        private val converter = filteringConverter()

        @Test
        fun `the object is parsed with think blocks, prose and fences around it`() {
            val rex = """{"name": "Rex"}"""
            val outputs = listOf(
                rex,
                "<think>I am thinking</think>\n$rex",
                "I am thinking\n$rex",
                "Based on my research, I found one match.\n\n```json\n$rex\n```",
                "```\n$rex\n```",
                "$rex\n\nLet me know if you need anything else.",
                "```json\n{\n  \"name\": \"Rex\"\n}\n```",
                "<think>weighing the options</think>\nHere is the answer.\n```json\n$rex\n```\nHope that helps.",
                "<think>First thinking block</think>\n//THINKING: Second thinking block\n$rex",
                "<think>a</think>$rex<think>a</think>",
                "<think>x } y</think>$rex<think>x } y</think>",
                "<think>a</think>$rex<think>b</think>",
            )
            outputs.forEach { output ->
                assertEquals("Rex", converter.convert(output).name, "LLM output: $output")
            }
        }

        @Test
        fun `output that is already valid is not changed`() {
            // Expected: "Sir Rex". The trailing space is valid JSON, so nothing is removed
            assertEquals("Sir Rex", converter.convert("""{"name": "Sir Rex"} """).name)
        }

        @Test
        fun `every combination of text before, value and text after keeps the value`() {
            // The text before and after the object also appears inside the values
            val before = listOf("", " ", ".", "Sure! ", "Rex", "<think>I am thinking</think> ", "```json\n")
            val values = listOf("Rex", "Sir Rex", "Rex Jr.", "Sure! Rex", "a } b", "a { b")
            val after = listOf("", ".", "Rex", "\n```", "<think>I am thinking</think>")
            for (b in before) for (value in values) for (a in after) {
                val output = "$b{\"name\": \"$value\"}$a"
                assertEquals(value, converter.convert(output).name, "LLM output: $output")
            }
        }

        @Test
        fun `think blocks before and after the object are removed`() {
            val rex = """{"name": "Rex"}"""
            val outputs = listOf(
                "<think></think>$rex",
                "<think>\nline 1\nline 2\n</think>\n$rex",
                "<think>never closed\n$rex",
                "$rex<think>after</think>",
                "<think>a</think><think>b</think>$rex",
                "<think>a</think> x <think>b</think>$rex",
                // An object inside a think block is not the answer
                "<think>{\"name\": \"Wrong\"}</think>$rex",
                // Other tags are not think blocks; they go as text before the object
                "<THINK>upper</THINK>$rex",
                "<thinking>hmm</thinking>$rex",
            )
            outputs.forEach { output ->
                assertEquals("Rex", converter.convert(output).name, "LLM output: $output")
            }
        }

        @Test
        fun `quotes, non-ASCII text, line breaks and markdown around the object`() {
            val cases = listOf(
                "\"{\"name\": \"Rex\"}\"" to "Rex",
                ", {\"name\": \"Rex, Sir\"}" to "Rex, Sir",
                "Sí. {\"name\": \"Señor Rex.\"}." to "Señor Rex.",
                "Here: {\"name\": \"Rex \\\"the dog\\\"\"}" to "Rex \"the dog\"",
                "\r\n{\"name\": \"Rex\"}\r\n" to "Rex",
                "**Answer**\n\n{\"name\": \"Rex\"}\n\n---" to "Rex",
                "{\n  \"name\": \"Sir Rex\"\n}\n\nDone." to "Sir Rex",
                "<think>maybe {\"name\": \"Wrong\"}?</think>\n{\"name\": \"Sir Rex\"}" to "Sir Rex",
            )
            cases.forEach { (output, expected) ->
                assertEquals(expected, converter.convert(output).name, "LLM output: $output")
            }
        }

        @Test
        fun `a think block inside a value is kept when the output is already valid`() {
            val result = converter.convert("""{"name": "a <think>x</think> b"}""")
            // Expected: the value as written. Valid output is parsed as it is, with nothing removed
            assertEquals("a <think>x</think> b", result.name)
        }
    }

    /**
     * Negative cases: the output has no usable object, so the result is a parse error
     * and never an object with a made-up or damaged value.
     */
    @Nested
    inner class NegativeCases {

        @Test
        fun `output without a usable object is a parse error`() {
            val outputs = listOf(
                "",
                "   ",
                "I could not find anything relevant.",
                "<think>only thinking</think>",
                "<think>I am thinking</think>\nNo JSON here.",
                // Cut off before the closing brace
                "{\"name\": \"Rex\"",
                // Not JSON
                "{name: Rex}",
                // The required field is missing
                "{}",
                "[]",
                // Two objects: no way to tell which one is the answer
                "{\"name\": \"Rex\"} {\"name\": \"Max\"}",
            )
            val converter = filteringConverter()
            outputs.forEach { output ->
                assertThrows<RuntimeException>("LLM output: $output") { converter.convert(output) }
            }
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
        fun `identical repeated blocks are all removed`() {
            // Expected: every copy of the block is removed, as before this change
            assertEquals("Hello  world ", stringWithoutThinkBlocks("Hello <think>a</think> world <think>a</think>"))
            assertEquals("Answer one", stringWithoutThinkBlocks("<think></think>Answer one<think></think>"))
        }

        @Test
        fun `block at the end`() {
            assertEquals("Answer ", stringWithoutThinkBlocks("Answer <think>x</think>"))
        }

        @Test
        fun `multi-line block`() {
            assertEquals("\nAnswer", stringWithoutThinkBlocks("<think>\nline 1\nline 2\n</think>\nAnswer"))
        }

        @Test
        fun `unclosed block is left as it is`() {
            val input = "<think>never closed\nAnswer"
            assertEquals(input, stringWithoutThinkBlocks(input))
        }

        @Test
        fun `text around a JSON object is kept`() {
            // A String answer is not parsed, so there is no object to extract: only <think> blocks
            // are removed. "Sure. " and the final "." are part of the answer and stay.
            val input = """Sure. {"name": "Rex Jr."}."""
            assertEquals(input, stringWithoutThinkBlocks(input))
        }

        @Test
        fun `positive - think blocks are removed wherever they are`() {
            val cases = listOf(
                "Hello <think>x</think>world" to "Hello world",
                "<think></think>Answer" to "Answer",
                "<think>{\"a\": 1}</think>Answer" to "Answer",
                "<think>a</think>A<think>a</think>B<think>a</think>" to "AB",
                "<think>x</think>" to "",
            )
            cases.forEach { (input, expected) -> assertEquals(expected, stringWithoutThinkBlocks(input)) }
        }

        @Test
        fun `negative - text that is not a complete think block is left as it is`() {
            val inputs = listOf(
                "",
                "Answer</think>",
                "<THINK>x</THINK>Answer",
                "<thinking>x</thinking>Answer",
                "a < b and b > c",
            )
            inputs.forEach { input -> assertEquals(input, stringWithoutThinkBlocks(input)) }
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
