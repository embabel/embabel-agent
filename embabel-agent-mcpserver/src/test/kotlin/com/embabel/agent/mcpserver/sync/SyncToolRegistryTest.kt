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

import io.mockk.*
import io.modelcontextprotocol.server.McpSyncServer
import com.embabel.agent.mcpserver.support.toolNames
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class SyncToolRegistryTest {

    private lateinit var mockSyncServer: McpSyncServer
    private lateinit var toolRegistry: SyncToolRegistry

    @BeforeEach
    fun setUp() {
        // Mock the extension function at the top level
        mockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
        mockSyncServer = mockk(relaxed = true)
        toolRegistry = SyncToolRegistry(mockSyncServer)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
    }

    @Test
    fun `getToolNames should return tools from server`() {
        val expectedTools = listOf("tool1", "tool2", "tool3")
        every { mockSyncServer.toolNames() } returns expectedTools

        val result = toolRegistry.getToolNames()

        assertEquals(expectedTools, result)
    }

    @Test
    fun `getToolNames should handle empty tool list`() {
        every { mockSyncServer.toolNames() } returns emptyList()

        val result = toolRegistry.getToolNames()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `getToolCount should return correct count`() {
        val tools = listOf("tool1", "tool2", "tool3")
        every { mockSyncServer.toolNames() } returns tools

        val result = toolRegistry.getToolCount()

        assertEquals(3, result)
    }

    @Test
    fun `getToolCount should return zero for empty tool list`() {
        every { mockSyncServer.toolNames() } returns emptyList()

        val result = toolRegistry.getToolCount()

        assertEquals(0, result)
    }

    @Test
    fun `hasToolNamed should return true when tool exists`() {
        val tools = listOf("tool1", "tool2", "tool3")
        every { mockSyncServer.toolNames() } returns tools

        assertTrue(toolRegistry.hasToolNamed("tool2"))
    }

    @Test
    fun `hasToolNamed should return false when tool does not exist`() {
        val tools = listOf("tool1", "tool2", "tool3")
        every { mockSyncServer.toolNames() } returns tools

        assertFalse(toolRegistry.hasToolNamed("nonExistentTool"))
    }

    @Test
    fun `hasToolNamed should return false for empty tool list`() {
        every { mockSyncServer.toolNames() } returns emptyList()

        assertFalse(toolRegistry.hasToolNamed("anyTool"))
    }

    @Test
    fun `hasToolNamed should handle null or empty tool names`() {
        val tools = listOf("tool1", "", "tool3")
        every { mockSyncServer.toolNames() } returns tools

        assertTrue(toolRegistry.hasToolNamed(""))
        assertFalse(toolRegistry.hasToolNamed("null"))
    }

    @Test
    fun `hasToolNamed should be case sensitive`() {
        val tools = listOf("Tool1", "tool2", "TOOL3")
        every { mockSyncServer.toolNames() } returns tools

        assertTrue(toolRegistry.hasToolNamed("Tool1"))
        assertFalse(toolRegistry.hasToolNamed("tool1"))
        assertTrue(toolRegistry.hasToolNamed("TOOL3"))
        assertFalse(toolRegistry.hasToolNamed("tool3"))
    }

    @Test
    fun `operations should be consistent across multiple calls`() {
        val tools = listOf("tool1", "tool2")
        every { mockSyncServer.toolNames() } returns tools

        // Multiple calls should return consistent results
        assertEquals(tools, toolRegistry.getToolNames())
        assertEquals(tools, toolRegistry.getToolNames())

        assertEquals(2, toolRegistry.getToolCount())
        assertEquals(2, toolRegistry.getToolCount())

        assertTrue(toolRegistry.hasToolNamed("tool1"))
        assertTrue(toolRegistry.hasToolNamed("tool1"))
    }

    @Test
    fun `getToolCount should match getToolNames size`() {
        val tools = listOf("tool1", "tool2", "tool3", "tool4", "tool5")
        every { mockSyncServer.toolNames() } returns tools

        assertEquals(toolRegistry.getToolNames().size, toolRegistry.getToolCount())
    }

    @Test
    fun `hasToolNamed should work with all tools from getToolNames`() {
        val tools = listOf("alpha", "beta", "gamma", "delta")
        every { mockSyncServer.toolNames() } returns tools

        // All tools returned by getToolNames should be found by hasToolNamed
        tools.forEach { toolName ->
            assertTrue(
                toolRegistry.hasToolNamed(toolName),
                "Tool '$toolName' should be found by hasToolNamed"
            )
        }
    }
}
