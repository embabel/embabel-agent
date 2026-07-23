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
import com.embabel.agent.spi.logging.DefaultColorPalette
import com.embabel.common.util.color
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies [formatProcessOutput] formatting for JSON, text, Markdown, links, and line wrapping. */
class FormatProcessOutputTest {

    private val palette = DefaultColorPalette()

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
            val prettyJson = """
                {
                  "answer" : 42
                }
            """.trimIndent()
            val expectedFragments = listOf(
                "process-info",
                "user question",
                prettyJson,
                "cost-info",
                "tools-info",
            )
            val fragmentIndexes = expectedFragments.map(result::indexOf)

            assertTrue(fragmentIndexes.all { it >= 0 }, "Missing expected fragment")
            assertTrue(
                fragmentIndexes.zipWithNext().all { (first, second) -> first < second },
                "Fragments are out of order",
            )
        }
    }

    @Nested
    inner class HasContentOutput {

        @Test
        fun `formats plain content`() {
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
            assertFalse(result.contains("\u001B[36m\u001B[1m"))
        }

        @Test
        fun `converts markdown heading to ANSI styling`() {
            val output = object : HasContent {
                override val content: String = "# Title\nBody text"
            }

            val result = formatProcessOutput(
                result = execution(output),
                colorPalette = palette,
                objectMapper = jacksonObjectMapper(),
                lineLength = 80,
            )

            assertTrue(result.contains("\u001B[36m\u001B[1m# Title\u001B[22m\u001B[0m\nBody text"))
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
            val expectedLinks = """
                - https://example.com: ${"Example".color(palette.color2)}
                - https://example.org: ${"Second".color(palette.color2)}
            """.trimIndent()

            assertTrue(result.contains(expectedLinks))
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
