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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.ToolDecorator
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [ToolResolutionHelper].
 * Verifies tool resolution from interactions and decoration via ToolDecorator.
 */
class ToolResolutionHelperTest {

    private lateinit var mockAgentProcess: AgentProcess
    private lateinit var mockProcessContext: ProcessContext
    private lateinit var mockAction: Action
    private lateinit var mockToolDecorator: ToolDecorator

    @BeforeEach
    fun setUp() {
        mockAgentProcess = mockk(relaxed = true)
        mockProcessContext = mockk(relaxed = true)
        mockAction = mockk(relaxed = true)
        mockToolDecorator = mockk(relaxed = true)

        every { mockAgentProcess.processContext } returns mockProcessContext
        every { mockProcessContext.platformServices.agentPlatform.toolGroupResolver } returns
            RegistryToolGroupResolver("test", emptyList())
    }

    private fun createMockTool(name: String): Tool {
        val definition = mockk<Tool.Definition>(relaxed = true)
        every { definition.name } returns name
        val tool = mockk<Tool>(relaxed = true)
        every { tool.definition } returns definition
        return tool
    }

    @Test
    fun `should resolve tools from interaction`() {
        // Given
        val tool1 = createMockTool("tool1")
        val tool2 = createMockTool("tool2")
        val interaction = LlmInteraction(
            id = InteractionId("test"),
            tools = listOf(tool1, tool2),
        )
        every { mockToolDecorator.decorate(any(), any(), any(), any()) } answers { firstArg() }

        // When
        val result = ToolResolutionHelper.resolveAndDecorate(
            interaction, mockAgentProcess, mockAction, mockToolDecorator
        )

        // Then
        assertEquals(2, result.size)
    }

    @Test
    fun `should decorate each resolved tool`() {
        // Given
        val tool1 = createMockTool("tool1")
        val tool2 = createMockTool("tool2")
        val interaction = LlmInteraction(
            id = InteractionId("test"),
            tools = listOf(tool1, tool2),
        )
        every { mockToolDecorator.decorate(any(), any(), any(), any()) } answers { firstArg() }

        // When
        ToolResolutionHelper.resolveAndDecorate(
            interaction, mockAgentProcess, mockAction, mockToolDecorator
        )

        // Then
        verify { mockToolDecorator.decorate(tool1, mockAgentProcess, mockAction, any()) }
        verify { mockToolDecorator.decorate(tool2, mockAgentProcess, mockAction, any()) }
    }

    @Test
    fun `should pass llmOptions to decorator`() {
        // Given
        val tool = createMockTool("tool1")
        val llmOptions = LlmOptions()
        val interaction = LlmInteraction(
            id = InteractionId("test"),
            tools = listOf(tool),
            llm = llmOptions,
        )
        every { mockToolDecorator.decorate(any(), any(), any(), any()) } answers { firstArg() }

        // When
        ToolResolutionHelper.resolveAndDecorate(
            interaction, mockAgentProcess, mockAction, mockToolDecorator
        )

        // Then
        verify { mockToolDecorator.decorate(tool, mockAgentProcess, mockAction, llmOptions) }
    }

    @Test
    fun `should handle null action`() {
        // Given
        val tool = createMockTool("tool1")
        val interaction = LlmInteraction(
            id = InteractionId("test"),
            tools = listOf(tool),
        )
        every { mockToolDecorator.decorate(any(), any(), any(), any()) } answers { firstArg() }

        // When
        val result = ToolResolutionHelper.resolveAndDecorate(
            interaction, mockAgentProcess, null, mockToolDecorator
        )

        // Then
        assertEquals(1, result.size)
        verify { mockToolDecorator.decorate(tool, mockAgentProcess, null, any()) }
    }

    @Test
    fun `should return empty list when no tools`() {
        // Given
        val interaction = LlmInteraction(id = InteractionId("test"))

        // When
        val result = ToolResolutionHelper.resolveAndDecorate(
            interaction, mockAgentProcess, mockAction, mockToolDecorator
        )

        // Then
        assertTrue(result.isEmpty())
    }
}
