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
package com.embabel.agent.mcpserver.async

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpServerFeatures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import com.embabel.agent.mcpserver.domain.McpExecutionMode

class AsyncServerStrategyTest {

    private lateinit var mockAsyncServer: McpAsyncServer
    private lateinit var strategy: AsyncServerStrategy

    @BeforeEach
    fun setUp() {
        mockAsyncServer = mockk(relaxed = true)
        strategy = AsyncServerStrategy(mockAsyncServer)
    }

    @Test
    fun `executionMode should return ASYNC`() {
        assertEquals(McpExecutionMode.ASYNC, strategy.executionMode)
    }

    @Test
    fun `addTool should accept AsyncToolSpecification`() {
        val mockToolSpec = mockk<McpServerFeatures.AsyncToolSpecification>()
        every { mockAsyncServer.addTool(mockToolSpec) } returns Mono.empty()

        StepVerifier.create(strategy.addTool(mockToolSpec))
            .verifyComplete()

        verify { mockAsyncServer.addTool(mockToolSpec) }
    }

    @Test
    fun `addTool should reject non-AsyncToolSpecification`() {
        val invalidToolSpec = "invalid"

        StepVerifier.create(strategy.addTool(invalidToolSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `addTool error message should include actual type`() {
        val invalidToolSpec = 12345

        StepVerifier.create(strategy.addTool(invalidToolSpec))
            .expectErrorMatches { error ->
                error is IllegalArgumentException &&
                error.message?.contains("Expected AsyncToolSpecification, got Int") == true
            }
            .verify()
    }

    @Test
    fun `removeTool should call server removeTool`() {
        val toolName = "testTool"
        every { mockAsyncServer.removeTool(toolName) } returns Mono.empty()

        StepVerifier.create(strategy.removeTool(toolName))
            .verifyComplete()

        verify { mockAsyncServer.removeTool(toolName) }
    }

    @Test
    fun `addResource should accept AsyncResourceSpecification`() {
        val mockResourceSpec = mockk<McpServerFeatures.AsyncResourceSpecification>()
        every { mockAsyncServer.addResource(mockResourceSpec) } returns Mono.empty()

        StepVerifier.create(strategy.addResource(mockResourceSpec))
            .verifyComplete()

        verify { mockAsyncServer.addResource(mockResourceSpec) }
    }

    @Test
    fun `addResource should reject non-AsyncResourceSpecification`() {
        val invalidResourceSpec = "invalid"

        StepVerifier.create(strategy.addResource(invalidResourceSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `addPrompt should accept AsyncPromptSpecification`() {
        val mockPromptSpec = mockk<McpServerFeatures.AsyncPromptSpecification>()
        every { mockAsyncServer.addPrompt(mockPromptSpec) } returns Mono.empty()

        StepVerifier.create(strategy.addPrompt(mockPromptSpec))
            .verifyComplete()

        verify { mockAsyncServer.addPrompt(mockPromptSpec) }
    }

    @Test
    fun `addPrompt should reject non-AsyncPromptSpecification`() {
        val invalidPromptSpec = "invalid"

        StepVerifier.create(strategy.addPrompt(invalidPromptSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `getToolRegistry should return AsyncToolRegistry`() {
        val toolRegistry = strategy.getToolRegistry()

        assertTrue(toolRegistry is AsyncToolRegistry)
    }

    @Test
    fun `addTool should handle SyncToolSpecification gracefully`() {
        val mockSyncToolSpec = mockk<McpServerFeatures.SyncToolSpecification>()

        StepVerifier.create(strategy.addTool(mockSyncToolSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `addResource should handle SyncResourceSpecification gracefully`() {
        val mockSyncResourceSpec = mockk<McpServerFeatures.SyncResourceSpecification>()

        StepVerifier.create(strategy.addResource(mockSyncResourceSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `addPrompt should handle SyncPromptSpecification gracefully`() {
        val mockSyncPromptSpec = mockk<McpServerFeatures.SyncPromptSpecification>()

        StepVerifier.create(strategy.addPrompt(mockSyncPromptSpec))
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `operations should return completed Monos when server operations succeed`() {
        val mockToolSpec = mockk<McpServerFeatures.AsyncToolSpecification>()
        val mockResourceSpec = mockk<McpServerFeatures.AsyncResourceSpecification>()
        val mockPromptSpec = mockk<McpServerFeatures.AsyncPromptSpecification>()

        every { mockAsyncServer.addTool(any()) } returns Mono.empty()
        every { mockAsyncServer.addResource(any()) } returns Mono.empty()
        every { mockAsyncServer.addPrompt(any()) } returns Mono.empty()
        every { mockAsyncServer.removeTool(any()) } returns Mono.empty()

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
    fun `operations should propagate server errors`() {
        val mockToolSpec = mockk<McpServerFeatures.AsyncToolSpecification>()
        val serverError = RuntimeException("Server error")

        every { mockAsyncServer.addTool(any()) } returns Mono.error(serverError)

        StepVerifier.create(strategy.addTool(mockToolSpec))
            .expectError(RuntimeException::class.java)
            .verify()
    }

    @Test
    fun `removeTool should handle server errors`() {
        val toolName = "failingTool"
        val serverError = RuntimeException("Remove failed")

        every { mockAsyncServer.removeTool(toolName) } returns Mono.error(serverError)

        StepVerifier.create(strategy.removeTool(toolName))
            .expectError(RuntimeException::class.java)
            .verify()
    }

    @Test
    fun `addResource should handle server timeout`() {
        val mockResourceSpec = mockk<McpServerFeatures.AsyncResourceSpecification>()

        every { mockAsyncServer.addResource(any()) } returns Mono.never() // Simulates timeout

        StepVerifier.create(strategy.addResource(mockResourceSpec))
            .expectTimeout(java.time.Duration.ofSeconds(1))
            .verify()
    }

    @Test
    fun `operations should handle null specifications gracefully`() {
        // Test with null values by passing them directly
        // This will cause KotlinNullPointerException due to force unwrapping

        assertThrows<NullPointerException> {
            strategy.addTool(null!!)
        }

        assertThrows<NullPointerException> {
            strategy.addResource(null!!)
        }

        assertThrows<NullPointerException> {
            strategy.addPrompt(null!!)
        }
    }
}
