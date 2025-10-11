/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.mcpserver.sync

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import reactor.test.StepVerifier
import com.embabel.agent.mcpserver.domain.McpExecutionMode

class SyncServerStrategyTest {

    private lateinit var mockSyncServer: McpSyncServer
    private lateinit var strategy: SyncServerStrategy

    @BeforeEach
    fun setUp() {
        mockSyncServer = mockk(relaxed = true)
        strategy = SyncServerStrategy(mockSyncServer)
    }

    @Test
    fun `executionMode should return SYNC`() {
        assertEquals(McpExecutionMode.SYNC, strategy.executionMode)
    }

    @Test
    fun `addTool should accept SyncToolSpecification`() {
        val mockToolSpec = mockk<McpServerFeatures.SyncToolSpecification>()

        StepVerifier.create(strategy.addTool(mockToolSpec))
            .verifyComplete()

        verify { mockSyncServer.addTool(mockToolSpec) }
    }

    @Test
    fun `addTool should reject non-SyncToolSpecification`() {
        val invalidToolSpec = "invalid"

        StepVerifier.create(strategy.addTool(invalidToolSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `removeTool should call server removeTool`() {
        val toolName = "testTool"

        StepVerifier.create(strategy.removeTool(toolName))
            .verifyComplete()

        verify { mockSyncServer.removeTool(toolName) }
    }

    @Test
    fun `addResource should accept SyncResourceSpecification`() {
        val mockResourceSpec = mockk<McpServerFeatures.SyncResourceSpecification>()

        StepVerifier.create(strategy.addResource(mockResourceSpec))
            .verifyComplete()

        verify { mockSyncServer.addResource(mockResourceSpec) }
    }

    @Test
    fun `addResource should reject non-SyncResourceSpecification`() {
        val invalidResourceSpec = "invalid"

        StepVerifier.create(strategy.addResource(invalidResourceSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `addPrompt should accept SyncPromptSpecification`() {
        val mockPromptSpec = mockk<McpServerFeatures.SyncPromptSpecification>()

        StepVerifier.create(strategy.addPrompt(mockPromptSpec))
            .verifyComplete()

        verify { mockSyncServer.addPrompt(mockPromptSpec) }
    }

    @Test
    fun `addPrompt should reject non-SyncPromptSpecification`() {
        val invalidPromptSpec = "invalid"

        StepVerifier.create(strategy.addPrompt(invalidPromptSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `getToolRegistry should return SyncToolRegistry`() {
        val toolRegistry = strategy.getToolRegistry()

        assertTrue(toolRegistry is SyncToolRegistry)
    }

    @Test
    fun `addTool should handle AsyncToolSpecification gracefully`() {
        val mockAsyncToolSpec = mockk<McpServerFeatures.AsyncToolSpecification>()

        StepVerifier.create(strategy.addTool(mockAsyncToolSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `addResource should handle AsyncResourceSpecification gracefully`() {
        val mockAsyncResourceSpec = mockk<McpServerFeatures.AsyncResourceSpecification>()

        StepVerifier.create(strategy.addResource(mockAsyncResourceSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `addPrompt should handle AsyncPromptSpecification gracefully`() {
        val mockAsyncPromptSpec = mockk<McpServerFeatures.AsyncPromptSpecification>()

        StepVerifier.create(strategy.addPrompt(mockAsyncPromptSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `operations should complete synchronously when server operations succeed`() {
        val mockToolSpec = mockk<McpServerFeatures.SyncToolSpecification>()
        val mockResourceSpec = mockk<McpServerFeatures.SyncResourceSpecification>()
        val mockPromptSpec = mockk<McpServerFeatures.SyncPromptSpecification>()

        // Test that all operations complete without error
        StepVerifier.create(strategy.addTool(mockToolSpec))
            .verifyComplete()

        StepVerifier.create(strategy.addResource(mockResourceSpec))
            .verifyComplete()

        StepVerifier.create(strategy.addPrompt(mockPromptSpec))
            .verifyComplete()

        StepVerifier.create(strategy.removeTool("testTool"))
            .verifyComplete()
    }

    @Test
    fun `operations should handle server exceptions`() {
        val mockToolSpec = mockk<McpServerFeatures.SyncToolSpecification>()
        val exception = RuntimeException("Server error")

        every { mockSyncServer.addTool(any()) } throws exception

        // The exception should propagate through the Mono
        assertThrows<RuntimeException> {
            strategy.addTool(mockToolSpec).block()
        }
    }
}
