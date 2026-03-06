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
import com.embabel.agent.api.common.autonomy.NoAgentFound
import com.embabel.agent.api.common.ranking.Rankings
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.shell.config.ShellProperties
import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.LoggingPersonality
import com.embabel.common.ai.model.ModelProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Tests for the ToolCallContext-related shell commands introduced in PR #1462 (issue #1323):
 * set-context, show-context, and the -c flag on execute.
 */
class ShellCommandsContextTest {

    private val autonomy: Autonomy = mockk(relaxed = true)
    private val modelProvider: ModelProvider = mockk(relaxed = true)
    private val terminalServices: TerminalServices = mockk(relaxed = true)
    private val environment: ConfigurableEnvironment = mockk(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val colorPalette: ColorPalette = object : ColorPalette {
        // Use no-op colors for testing (ANSI escape won't affect assertions)
        override val highlight: Int = 0xbeb780
        override val color2: Int = 0x7da17e
    }
    private val loggingPersonality: LoggingPersonality = mockk(relaxed = true) {
        every { logger } returns mockk(relaxed = true)
        every { colorPalette } returns this@ShellCommandsContextTest.colorPalette
    }
    private val toolsStats: ToolsStats = mockk(relaxed = true)
    private val context: ConfigurableApplicationContext = mockk(relaxed = true)
    private val agentPlatform: AgentPlatform = mockk(relaxed = true)
    private val autonomyProperties: AutonomyProperties = mockk(relaxed = true) {
        every { agentConfidenceCutOff } returns 0.6
        every { goalConfidenceCutOff } returns 0.6
    }

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
            shellProperties = ShellProperties(),
        )
    }

    @Nested
    inner class SetContext {

        @Test
        fun `sets context from key=value pairs`() {
            val result = shellCommands.setContext("tenantId=acme,apiKey=secret123")
            assertTrue(result.contains("tenantId"))
            assertTrue(result.contains("acme"))
            assertTrue(result.contains("apiKey"))
            assertTrue(result.contains("secret123"))
        }

        @Test
        fun `clears context when input is 'clear'`() {
            // First set something
            shellCommands.setContext("tenantId=acme")
            // Then clear
            val result = shellCommands.setContext("clear")
            assertTrue(result.contains("cleared"))
        }

        @Test
        fun `clears context when input is blank`() {
            shellCommands.setContext("tenantId=acme")
            val result = shellCommands.setContext("")
            assertTrue(result.contains("cleared"))
        }

        @Test
        fun `handles value containing equals sign`() {
            val result = shellCommands.setContext("token=abc=def")
            assertTrue(result.contains("token"))
            assertTrue(result.contains("abc=def"))
        }

        @Test
        fun `ignores entries without equals sign`() {
            shellCommands.setContext("validKey=value,invalidEntry")
            val showResult = shellCommands.showContext()
            assertTrue(showResult.contains("validKey"))
            assertFalse(showResult.contains("invalidEntry"))
        }

        @Test
        fun `trims whitespace around keys and values`() {
            shellCommands.setContext(" tenantId = acme , apiKey = secret ")
            val showResult = shellCommands.showContext()
            assertTrue(showResult.contains("tenantId"))
            assertTrue(showResult.contains("acme"))
        }
    }

    @Nested
    inner class ShowContext {

        @Test
        fun `shows empty message when no context is set`() {
            val result = shellCommands.showContext()
            assertTrue(result.contains("empty"))
        }

        @Test
        fun `shows context entries after setContext`() {
            shellCommands.setContext("tenantId=acme,authToken=bearer-xyz")
            val result = shellCommands.showContext()
            assertTrue(result.contains("tenantId"))
            assertTrue(result.contains("acme"))
            assertTrue(result.contains("authToken"))
            assertTrue(result.contains("bearer-xyz"))
        }

        @Test
        fun `shows empty after clearing context`() {
            shellCommands.setContext("tenantId=acme")
            shellCommands.setContext("clear")
            val result = shellCommands.showContext()
            assertTrue(result.contains("empty"))
        }
    }

    @Nested
    inner class ExecuteContextMerge {

        @BeforeEach
        fun setUpAutonomyToThrow() {
            // Make autonomy throw NoAgentFound so we can test context propagation
            // without needing a full agent execution pipeline
            every {
                autonomy.chooseAndRunAgent(
                    intent = any(),
                    processOptions = any(),
                )
            } throws NoAgentFound(
                agentRankings = Rankings<Agent>(emptyList()),
                basis = "test",
            )
        }

        @Test
        fun `execute without context uses empty context`() {
            val capturedOptions = slot<ProcessOptions>()
            every {
                autonomy.chooseAndRunAgent(
                    intent = any(),
                    processOptions = capture(capturedOptions),
                )
            } throws NoAgentFound(
                agentRankings = Rankings<Agent>(emptyList()),
                basis = "test",
            )

            shellCommands.execute(
                intent = "test intent",
                showPrompts = false,
            )

            assertTrue(capturedOptions.captured.toolCallContext.isEmpty)
        }

        @Test
        fun `execute with persistent context propagates it`() {
            val capturedOptions = slot<ProcessOptions>()
            every {
                autonomy.chooseAndRunAgent(
                    intent = any(),
                    processOptions = capture(capturedOptions),
                )
            } throws NoAgentFound(
                agentRankings = Rankings<Agent>(emptyList()),
                basis = "test",
            )

            shellCommands.setContext("tenantId=acme")
            shellCommands.execute(
                intent = "test intent",
                showPrompts = false,
            )

            assertEquals("acme", capturedOptions.captured.toolCallContext.get<String>("tenantId"))
        }

        @Test
        fun `execute with per-execution context merges with persistent`() {
            val capturedOptions = slot<ProcessOptions>()
            every {
                autonomy.chooseAndRunAgent(
                    intent = any(),
                    processOptions = capture(capturedOptions),
                )
            } throws NoAgentFound(
                agentRankings = Rankings<Agent>(emptyList()),
                basis = "test",
            )

            shellCommands.setContext("tenantId=acme,authToken=xyz")
            shellCommands.execute(
                intent = "test intent",
                showPrompts = false,
                context = "correlationId=req-123",
            )

            val ctx = capturedOptions.captured.toolCallContext
            assertEquals("acme", ctx.get<String>("tenantId"))
            assertEquals("xyz", ctx.get<String>("authToken"))
            assertEquals("req-123", ctx.get<String>("correlationId"))
        }

        @Test
        fun `per-execution context wins on conflict with persistent`() {
            val capturedOptions = slot<ProcessOptions>()
            every {
                autonomy.chooseAndRunAgent(
                    intent = any(),
                    processOptions = capture(capturedOptions),
                )
            } throws NoAgentFound(
                agentRankings = Rankings<Agent>(emptyList()),
                basis = "test",
            )

            shellCommands.setContext("tenantId=acme")
            shellCommands.execute(
                intent = "test intent",
                showPrompts = false,
                context = "tenantId=beta",
            )

            assertEquals("beta", capturedOptions.captured.toolCallContext.get<String>("tenantId"))
        }

        @Test
        fun `persistent context is unchanged after per-execution override`() {
            every {
                autonomy.chooseAndRunAgent(
                    intent = any(),
                    processOptions = any(),
                )
            } throws NoAgentFound(
                agentRankings = Rankings<Agent>(emptyList()),
                basis = "test",
            )

            shellCommands.setContext("tenantId=acme")
            shellCommands.execute(
                intent = "test intent",
                showPrompts = false,
                context = "tenantId=beta",
            )

            // Persistent context should still be "acme"
            val showResult = shellCommands.showContext()
            assertTrue(showResult.contains("acme"))
            assertFalse(showResult.contains("beta"))
        }
    }
}
