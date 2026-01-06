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
