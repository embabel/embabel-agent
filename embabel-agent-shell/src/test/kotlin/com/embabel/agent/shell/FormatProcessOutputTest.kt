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
package com.embabel.agent.shell

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.domain.library.InternetResource
import com.embabel.agent.domain.library.InternetResources
import com.embabel.agent.spi.logging.ColorPalette
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatProcessOutputTest {

    private val palette = object : ColorPalette {
        override val highlight: Int = 0xbeb780
        override val color2: Int = 0x7da17e
    }

    private fun execution(output: Any, basis: Any = "user question"): AgentProcessExecution {
        val process = mockk<AgentProcess>(relaxed = true)
        every { process.infoString(verbose = true) } returns "process-info"
        every { process.costInfoString(verbose = true) } returns "cost-info"
        every { process.toolsStats.infoString(verbose = true) } returns "tools-info"

        val execution = mockk<AgentProcessExecution>()
        every { execution.output } returns output
        every { execution.basis } returns basis
        every { execution.agentProcess } returns process
        return execution
    }

    @Nested
    inner class NonContentOutput {

        @Test
        fun `pretty prints arbitrary output as json`() {
            val result = formatProcessOutput(
                result = execution(mapOf("answer" to 42)),
                colorPalette = palette,
                objectMapper = jacksonObjectMapper(),
                lineLength = 80,
            )

            assertTrue(result.contains("process-info"))
            assertTrue(result.contains("user question"))
            assertTrue(result.contains("\"answer\""))
            assertTrue(result.contains("42"))
            assertTrue(result.contains("cost-info"))
            assertTrue(result.contains("tools-info"))
        }
    }

    @Nested
    inner class HasContentOutput {

        @Test
        fun `wraps plain content without markdown heading conversion`() {
            val output = object : HasContent {
                override val content: String = "plain text answer without heading"
            }

            val result = formatProcessOutput(
                result = execution(output),
                colorPalette = palette,
                objectMapper = jacksonObjectMapper(),
                lineLength = 40,
            )

            assertTrue(result.contains("plain text answer without heading"))
            assertFalse(result.contains("\n#"))
        }

        @Test
        fun `converts markdown headings when content contains hash`() {
            val output = object : HasContent {
                override val content: String = "# Title\nBody text"
            }

            val result = formatProcessOutput(
                result = execution(output),
                colorPalette = palette,
                objectMapper = jacksonObjectMapper(),
                lineLength = 80,
            )

            assertTrue(result.contains("Title"))
            assertTrue(result.contains("Body text"))
        }

        @Test
        fun `appends internet resource links when output implements InternetResources`() {
            data class ContentWithLinks(
                override val content: String,
                override val links: List<InternetResource>,
            ) : HasContent, InternetResources

            val output = ContentWithLinks(
                content = "see links",
                links = listOf(
                    InternetResource(url = "https://example.com", summary = "Example"),
                    InternetResource(url = "https://example.org", summary = "Second"),
                ),
            )

            val result = formatProcessOutput(
                result = execution(output),
                colorPalette = palette,
                objectMapper = jacksonObjectMapper(),
                lineLength = 80,
            )

            assertTrue(result.contains("https://example.com"))
            assertTrue(result.contains("Example"))
            assertTrue(result.contains("https://example.org"))
            assertTrue(result.contains("Second"))
            assertTrue(result.indexOf("https://example.com") < result.indexOf("https://example.org"))
        }

        @Test
        fun `wraps long plain content at configured line length`() {
            val output = object : HasContent {
                override val content: String = "alpha beta gamma delta"
            }

            val result = formatProcessOutput(
                result = execution(output),
                colorPalette = palette,
                objectMapper = jacksonObjectMapper(),
                lineLength = 10,
            )

            assertTrue(result.contains("alpha beta\ngamma\ndelta"))
        }
    }
}
