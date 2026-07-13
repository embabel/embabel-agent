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

import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.AutonomyProperties
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.shell.config.ShellProperties
import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.LoggingPersonality
import com.embabel.common.ai.model.ModelProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Regression tests for the agents shell command (issue #1741):
 * empty-state messaging and preserved good output when agents exist.
 */
class ShellCommandsAgentsTest {

    private val autonomy: Autonomy = mockk(relaxed = true)
    private val modelProvider: ModelProvider = mockk(relaxed = true)
    private val terminalServices: TerminalServices = mockk(relaxed = true)
    private val environment: ConfigurableEnvironment = mockk(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val colorPalette: ColorPalette = object : ColorPalette {
        override val highlight: Int = 0xbeb780
        override val color2: Int = 0x7da17e
    }
    private val loggingPersonality: LoggingPersonality = mockk(relaxed = true) {
        every { logger } returns mockk(relaxed = true)
        every { colorPalette } returns this@ShellCommandsAgentsTest.colorPalette
    }
    private val toolsStats: ToolsStats = mockk(relaxed = true)
    private val context: ConfigurableApplicationContext = mockk(relaxed = true)
    private val agentPlatform: AgentPlatform = mockk(relaxed = true)
    private val autonomyProperties: AutonomyProperties = mockk(relaxed = true)
    private val shellProperties = ShellProperties()

    private lateinit var shellCommands: ShellCommands

    @BeforeEach
    fun setUp() {
        every { autonomy.agentPlatform } returns agentPlatform
        every { autonomy.properties } returns autonomyProperties
        shellCommands = ShellCommands(
            autonomy = autonomy,
            modelProvider = modelProvider,
            terminalServices = terminalServices,
            environment = environment,
            objectMapper = objectMapper,
            colorPalette = colorPalette,
            loggingPersonality = loggingPersonality,
            toolsStats = toolsStats,
            context = context,
            shellProperties = shellProperties,
            asyncer = mockk(relaxed = true),
        )
    }

    private fun agent(
        name: String,
        description: String,
        provider: String = "test-provider",
    ) = Agent(
        name = name,
        provider = provider,
        description = description,
        actions = emptyList(),
        goals = emptySet(),
    )

    /** Strip ANSI escape codes so assertions ignore bold/color styling. */
    private fun String.stripAnsi(): String =
        replace(Regex("\u001B\\[[;\\d]*m"), "")

    @Nested
    inner class EmptyAgents {

        @Test
        fun `shows no agents registered when platform has no agents`() {
            every { agentPlatform.agents() } returns emptyList()

            val result = shellCommands.agents()

            assertEquals("No agents registered", result)
        }
    }

    @Nested
    inner class RegisteredAgents {

        @Test
        fun `single agent has detailed listing and summary`() {
            val demo = agent(name = "demo-agent", description = "A demo agent")
            every { agentPlatform.agents() } returns listOf(demo)

            val result = shellCommands.agents().stripAnsi()

            assertTrue(result.contains("Agents:"), "Expected Agents header, got: $result")
            assertTrue(result.contains("description: A demo agent"), "Expected detailed description, got: $result")
            assertTrue(result.contains("provider: test-provider"), "Expected provider in detail, got: $result")
            assertTrue(result.contains("name: demo-agent"), "Expected name in detail, got: $result")
            assertTrue(result.contains("Summary"), "Expected Summary section, got: $result")
            assertTrue(
                result.contains("demo-agent: A demo agent"),
                "Expected name:description summary line, got: $result",
            )
            assertTrue(
                result.indexOf("Agents:") < result.indexOf("Summary"),
                "Detail listing should come before Summary, got: $result",
            )
            assertTrue(
                result.indexOf("description: A demo agent") < result.indexOf("Summary"),
                "Verbose detail should appear before Summary, got: $result",
            )
            assertFalse(
                result.contains("No agents registered"),
                "Should not claim no agents when agents exist: $result",
            )
        }

        @Test
        fun `multiple agents are separated and all appear in summary`() {
            val poet = agent(name = "Poet", description = "Write poems")
            val coder = agent(name = "Coder", description = "Answer coding questions")
            every { agentPlatform.agents() } returns listOf(poet, coder)

            val result = shellCommands.agents().stripAnsi()
            val separator = "-".repeat(shellProperties.lineLength)

            assertTrue(result.contains("description: Write poems"), "Expected Poet detail, got: $result")
            assertTrue(
                result.contains("description: Answer coding questions"),
                "Expected Coder detail, got: $result",
            )
            assertTrue(
                result.contains(separator),
                "Expected separator between agents of length ${shellProperties.lineLength}, got: $result",
            )
            assertTrue(
                result.indexOf("description: Write poems") < result.indexOf(separator) &&
                    result.indexOf(separator) < result.indexOf("description: Answer coding questions"),
                "Separator should sit between agent detail blocks, got: $result",
            )

            val summaryStart = result.indexOf("Summary")
            assertTrue(summaryStart >= 0, "Expected Summary section, got: $result")
            val summary = result.substring(summaryStart)
            assertTrue(summary.contains("Poet: Write poems"), "Expected Poet in summary: $summary")
            assertTrue(
                summary.contains("Coder: Answer coding questions"),
                "Expected Coder in summary: $summary",
            )
            assertTrue(
                summary.indexOf("Poet: Write poems") < summary.indexOf("Coder: Answer coding questions"),
                "Summary should preserve agent order, got: $summary",
            )
        }

        @Test
        fun `summary is only name and description lines after the Summary header`() {
            val agent = agent(name = "demo-agent", description = "A demo agent")
            every { agentPlatform.agents() } returns listOf(agent)

            val result = shellCommands.agents().stripAnsi()
            val summaryLines = result.substringAfter("Summary").trim().lines()

            assertEquals(
                listOf("demo-agent: A demo agent"),
                summaryLines,
                "Summary should be only concise name:description lines",
            )
        }
    }
}
