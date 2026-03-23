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
package com.embabel.agent.mcpserver

import com.embabel.agent.mcpserver.domain.McpExecutionMode
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import reactor.core.publisher.Mono

class AbstractMcpServerConfigurationTest {

    private lateinit var mockApplicationContext: ConfigurableApplicationContext
    private lateinit var mockServerStrategy: McpServerStrategy
    private lateinit var mockToolRegistry: ToolRegistry
    private lateinit var configuration: TestAbstractMcpServerConfiguration

    @BeforeEach
    fun setUp() {
        mockApplicationContext = mockk(relaxed = true)
        mockServerStrategy = mockk(relaxed = true)
        mockToolRegistry = mockk(relaxed = true)
        configuration = TestAbstractMcpServerConfiguration(mockApplicationContext)
    }

    @Test
    fun `exposeMcpFunctionality should initialize server successfully`() {
        // Setup mocks
        every { mockServerStrategy.executionMode } returns McpExecutionMode.SYNC
        every { mockServerStrategy.getToolRegistry() } returns mockToolRegistry
        every { mockToolRegistry.getToolNames() } returns emptyList()
        every { mockServerStrategy.addTool(any()) } returns Mono.empty()
        every { mockServerStrategy.addResource(any()) } returns Mono.empty()
        every { mockServerStrategy.addPrompt(any()) } returns Mono.empty()

        configuration.mockServerStrategy = mockServerStrategy

        val event = mockk<AgentScanningBeanPostProcessorEvent>()

        // Execute
        configuration.exposeMcpFunctionality()

        // Verify initialization was called
        assertTrue(configuration.createServerStrategyCalled)
    }

    @Test
    fun `exposeMcpFunctionality should handle initialization errors gracefully`() {
        every { mockServerStrategy.executionMode } returns McpExecutionMode.SYNC
        every { mockServerStrategy.getToolRegistry() } throws RuntimeException("Test error")

        configuration.mockServerStrategy = mockServerStrategy

        val event = mockk<AgentScanningBeanPostProcessorEvent>()

        // Should not throw exception
        assertDoesNotThrow {
            configuration.exposeMcpFunctionality()
        }
    }

    @Test
    fun `should preserve helloBanner tool by default`() {
        every { mockToolRegistry.getToolNames() } returns listOf("helloBanner", "otherTool")
        every { mockServerStrategy.getToolRegistry() } returns mockToolRegistry
        every { mockServerStrategy.removeTool("otherTool") } returns Mono.empty()
        every { mockServerStrategy.addTool(any()) } returns Mono.empty()
        every { mockServerStrategy.addResource(any()) } returns Mono.empty()
        every { mockServerStrategy.addPrompt(any()) } returns Mono.empty()

        configuration.mockServerStrategy = mockServerStrategy

        configuration.exposeMcpFunctionality()

        // Should only remove "otherTool", not "helloBanner"
        verify(exactly = 1) { mockServerStrategy.removeTool("otherTool") }
        verify(exactly = 0) { mockServerStrategy.removeTool("helloBanner") }
    }

    @Test
    fun `should handle tool removal failures gracefully`() {
        every { mockToolRegistry.getToolNames() } returns listOf("tool1", "tool2")
        every { mockServerStrategy.getToolRegistry() } returns mockToolRegistry
        every { mockServerStrategy.removeTool("tool1") } returns Mono.error(RuntimeException("Remove failed"))
        every { mockServerStrategy.removeTool("tool2") } returns Mono.empty()
        every { mockServerStrategy.addTool(any()) } returns Mono.empty()
        every { mockServerStrategy.addResource(any()) } returns Mono.empty()
        every { mockServerStrategy.addPrompt(any()) } returns Mono.empty()

        configuration.mockServerStrategy = mockServerStrategy

        // Should not throw exception
        assertDoesNotThrow {
            configuration.exposeMcpFunctionality()
        }
    }

    @Test
    fun `should handle tool addition failures gracefully`() {
        val mockToolCallback = mockk<Any>()
        val mockToolSpec = mockk<Any>()

        every { mockToolRegistry.getToolNames() } returns emptyList()
        every { mockServerStrategy.getToolRegistry() } returns mockToolRegistry
        every { mockServerStrategy.addTool(any()) } returns Mono.error(RuntimeException("Add failed"))
        every { mockServerStrategy.addResource(any()) } returns Mono.empty()
        every { mockServerStrategy.addPrompt(any()) } returns Mono.empty()

        configuration.mockServerStrategy = mockServerStrategy
        configuration.mockToolCallbacks = listOf(mockToolCallback)
        configuration.mockToolSpecs = listOf(mockToolSpec)

        // Should not throw exception
        assertDoesNotThrow {
            configuration.exposeMcpFunctionality()
        }
    }

    @Test
    fun `should handle resource addition failures gracefully`() {
        val mockResourceSpec = mockk<Any>()

        every { mockToolRegistry.getToolNames() } returns emptyList()
        every { mockServerStrategy.getToolRegistry() } returns mockToolRegistry
        every { mockServerStrategy.addTool(any()) } returns Mono.empty()
        every { mockServerStrategy.addResource(any()) } returns Mono.error(RuntimeException("Resource add failed"))
        every { mockServerStrategy.addPrompt(any()) } returns Mono.empty()

        configuration.mockServerStrategy = mockServerStrategy
        configuration.mockResourceSpecs = listOf(mockResourceSpec)

        // Should not throw exception
        assertDoesNotThrow {
            configuration.exposeMcpFunctionality()
        }
    }

    @Test
    fun `should handle prompt addition failures gracefully`() {
        val mockPromptSpec = mockk<Any>()

        every { mockToolRegistry.getToolNames() } returns emptyList()
        every { mockServerStrategy.getToolRegistry() } returns mockToolRegistry
        every { mockServerStrategy.addTool(any()) } returns Mono.empty()
        every { mockServerStrategy.addResource(any()) } returns Mono.empty()
        every { mockServerStrategy.addPrompt(any()) } returns Mono.error(RuntimeException("Prompt add failed"))

        configuration.mockServerStrategy = mockServerStrategy
        configuration.mockPromptSpecs = listOf(mockPromptSpec)

        // Should not throw exception
        assertDoesNotThrow {
            configuration.exposeMcpFunctionality()
        }
    }

    @Test
    fun `shouldPreserveTool should preserve helloBanner by default`() {
        assertTrue(configuration.testShouldPreserveTool("helloBanner"))
        assertFalse(configuration.testShouldPreserveTool("otherTool"))
        assertFalse(configuration.testShouldPreserveTool(""))
        assertFalse(configuration.testShouldPreserveTool("HELLOBANNER"))
    }

    // Test implementation of abstract class
    inner class TestAbstractMcpServerConfiguration(
        applicationContext: ConfigurableApplicationContext,
    ) : AbstractMcpServerConfiguration(applicationContext) {

        override val logger: Logger = LoggerFactory.getLogger(TestAbstractMcpServerConfiguration::class.java)

        var createServerStrategyCalled = false
        var mockServerStrategy: McpServerStrategy? = null
        var mockToolCallbacks: List<Any> = emptyList()
        var mockToolSpecs: List<Any> = emptyList()
        var mockResourceSpecs: List<Any> = emptyList()
        var mockPromptSpecs: List<Any> = emptyList()
        var mockExecutionMode: String = "SYNC"

        override fun createServerStrategy(): McpServerStrategy {
            createServerStrategyCalled = true
            return mockServerStrategy ?: mockk(relaxed = true)
        }

        override fun createBannerTool(): ToolCallbackProvider = mockk()

        override fun getToolPublishers(): List<McpExportToolCallbackPublisher> {
            val mockPublisher = mockk<McpExportToolCallbackPublisher>()
            // Convert Any callbacks to ToolCallback with proper casting
            @Suppress("UNCHECKED_CAST")
            val toolCallbacks = mockToolCallbacks as List<org.springframework.ai.tool.ToolCallback>
            every { mockPublisher.toolCallbacks } returns toolCallbacks
            return listOf(mockPublisher)
        }

        override fun getResourcePublishers(): List<Any> = mockResourceSpecs

        override fun getPromptPublishers(): List<Any> = mockPromptSpecs

        override fun convertToToolSpecifications(toolCallbacks: List<Any>): List<Any> {
            // Cast to ToolCallback type as expected by the interface
            @Suppress("UNCHECKED_CAST")
            val callbacks = toolCallbacks as List<org.springframework.ai.tool.ToolCallback>
            return mockToolSpecs
        }

        override fun getExecutionMode(): String = mockExecutionMode

        // Expose protected methods for testing
        fun testShouldPreserveTool(toolName: String): Boolean = shouldPreserveTool(toolName)
    }
}
