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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolResponseContentAdapterTest {

    @Nested
    inner class PassthroughAdapterTests {

        private val adapter = ToolResponseContentAdapter.PASSTHROUGH

        @Test
        fun `passes plain text through unchanged`() {
            val content = "Enabled 4 tools: search, filter, sort, count"
            assertThat(adapter.adapt(content)).isEqualTo(content)
        }

        @Test
        fun `passes JSON object through unchanged`() {
            val content = """{"count": 5}"""
            assertThat(adapter.adapt(content)).isEqualTo(content)
        }

        @Test
        fun `passes JSON array through unchanged`() {
            val content = """[1, 2, 3]"""
            assertThat(adapter.adapt(content)).isEqualTo(content)
        }

        @Test
        fun `passes empty string through unchanged`() {
            assertThat(adapter.adapt("")).isEqualTo("")
        }

        @Test
        fun `passes whitespace through unchanged`() {
            assertThat(adapter.adapt("   ")).isEqualTo("   ")
        }
    }

    @Nested
    inner class JsonWrappingAdapterTests {

        private val adapter = JsonWrappingToolResponseContentAdapter()

        @Test
        fun `wraps plain text in JSON object`() {
            val result = adapter.adapt("Enabled 4 tools: search, filter, sort, count")

            assertThat(result).startsWith("{")
            assertThat(result).contains("\"result\"")
            assertThat(result).contains("Enabled 4 tools")
        }

        @Test
        fun `preserves JSON object content as-is`() {
            val content = """{"count": 5}"""
            assertThat(adapter.adapt(content)).isEqualTo(content)
        }

        @Test
        fun `preserves JSON array content as-is`() {
            val content = """[1, 2, 3]"""
            assertThat(adapter.adapt(content)).isEqualTo(content)
        }

        @Test
        fun `wraps whitespace-only content in JSON object`() {
            val result = adapter.adapt("   ")

            assertThat(result).startsWith("{")
            assertThat(result).contains("\"result\"")
        }

        @Test
        fun `wraps empty string in JSON object`() {
            val result = adapter.adapt("")

            assertThat(result).startsWith("{")
            assertThat(result).contains("\"result\"")
        }

        @Test
        fun `preserves JSON object with leading whitespace`() {
            val content = "  {\"count\": 5}"
            assertThat(adapter.adapt(content)).isEqualTo(content)
        }

        @Test
        fun `preserves JSON array with leading whitespace`() {
            val content = "  [1, 2, 3]"
            assertThat(adapter.adapt(content)).isEqualTo(content)
        }

        @Test
        fun `properly escapes special characters in wrapped content`() {
            val content = "Line 1\nLine 2\tTabbed \"quoted\""
            val result = adapter.adapt(content)

            assertThat(result).startsWith("{")
            assertThat(result).contains("\\n")
            assertThat(result).contains("\\t")
            assertThat(result).contains("\\\"quoted\\\"")
        }

        @Test
        fun `wraps RAG search results with real-world content`() {
            val content = "2 results: HBNB Services - Technical Blockchain Advisor"
            val result = adapter.adapt(content)

            assertThat(result).startsWith("{")
            assertThat(result).contains("\"result\"")
            assertThat(result).contains("HBNB Services")
        }
    }
}
