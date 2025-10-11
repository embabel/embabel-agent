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

import io.mockk.*
import io.modelcontextprotocol.server.McpAsyncServer
import com.embabel.agent.mcpserver.support.toolNames
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class AsyncToolRegistryTest {

    private lateinit var mockAsyncServer: McpAsyncServer
    private lateinit var toolRegistry: AsyncToolRegistry

    @BeforeEach
    fun setUp() {
        // Mock the extension function at the top level
        mockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
        mockAsyncServer = mockk()
        toolRegistry = AsyncToolRegistry(mockAsyncServer)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
    }

    @Test
    fun `getToolNames should return tools from server`() {
        val expectedTools = listOf("asyncTool1", "asyncTool2", "asyncTool3")
        every { mockAsyncServer.toolNames() } returns expectedTools

        val result = toolRegistry.getToolNames()

        assertEquals(expectedTools, result)
    }

    @Test
    fun `getToolNames should handle empty tool list`() {
        every { mockAsyncServer.toolNames() } returns emptyList()

        val result = toolRegistry.getToolNames()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `getToolCount should return correct count`() {
        val tools = listOf("asyncTool1", "asyncTool2", "asyncTool3", "asyncTool4")
        every { mockAsyncServer.toolNames() } returns tools

        val result = toolRegistry.getToolCount()

        assertEquals(4, result)
    }

    @Test
    fun `getToolCount should return zero for empty tool list`() {
        every { mockAsyncServer.toolNames() } returns emptyList()

        val result = toolRegistry.getToolCount()

        assertEquals(0, result)
    }

    @Test
    fun `hasToolNamed should return true when tool exists`() {
        val tools = listOf("asyncTool1", "asyncTool2", "asyncTool3")
        every { mockAsyncServer.toolNames() } returns tools

        assertTrue(toolRegistry.hasToolNamed("asyncTool2"))
    }

    @Test
    fun `hasToolNamed should return false when tool does not exist`() {
        val tools = listOf("asyncTool1", "asyncTool2", "asyncTool3")
        every { mockAsyncServer.toolNames() } returns tools

        assertFalse(toolRegistry.hasToolNamed("nonExistentAsyncTool"))
    }

    @Test
    fun `hasToolNamed should return false for empty tool list`() {
        every { mockAsyncServer.toolNames() } returns emptyList()

        assertFalse(toolRegistry.hasToolNamed("anyAsyncTool"))
    }

    @Test
    fun `hasToolNamed should handle special characters in tool names`() {
        val tools = listOf("async-tool-1", "async_tool_2", "async.tool.3", "async@tool#4")
        every { mockAsyncServer.toolNames() } returns tools

        assertTrue(toolRegistry.hasToolNamed("async-tool-1"))
        assertTrue(toolRegistry.hasToolNamed("async_tool_2"))
        assertTrue(toolRegistry.hasToolNamed("async.tool.3"))
        assertTrue(toolRegistry.hasToolNamed("async@tool#4"))
    }

    @Test
    fun `hasToolNamed should be case sensitive`() {
        val tools = listOf("AsyncTool1", "asyncTool2", "ASYNCTOOL3")
        every { mockAsyncServer.toolNames() } returns tools

        assertTrue(toolRegistry.hasToolNamed("AsyncTool1"))
        assertFalse(toolRegistry.hasToolNamed("asynctool1"))
        assertTrue(toolRegistry.hasToolNamed("ASYNCTOOL3"))
        assertFalse(toolRegistry.hasToolNamed("asynctool3"))
    }

    @Test
    fun `operations should be consistent across multiple calls`() {
        val tools = listOf("asyncTool1", "asyncTool2")
        every { mockAsyncServer.toolNames() } returns tools

        // Multiple calls should return consistent results
        assertEquals(tools, toolRegistry.getToolNames())
        assertEquals(tools, toolRegistry.getToolNames())

        assertEquals(2, toolRegistry.getToolCount())
        assertEquals(2, toolRegistry.getToolCount())

        assertTrue(toolRegistry.hasToolNamed("asyncTool1"))
        assertTrue(toolRegistry.hasToolNamed("asyncTool1"))
    }

    @Test
    fun `getToolCount should match getToolNames size`() {
        val tools = listOf("asyncTool1", "asyncTool2", "asyncTool3", "asyncTool4", "asyncTool5")
        every { mockAsyncServer.toolNames() } returns tools

        assertEquals(toolRegistry.getToolNames().size, toolRegistry.getToolCount())
    }

    @Test
    fun `hasToolNamed should work with all tools from getToolNames`() {
        val tools = listOf("alphaAsync", "betaAsync", "gammaAsync", "deltaAsync")
        every { mockAsyncServer.toolNames() } returns tools

        // All tools returned by getToolNames should be found by hasToolNamed
        tools.forEach { toolName ->
            assertTrue(
                toolRegistry.hasToolNamed(toolName),
                "Tool '$toolName' should be found by hasToolNamed"
            )
        }
    }

    @Test
    fun `should handle large number of tools efficiently`() {
        val tools = (1..1000).map { "asyncTool$it" }
        every { mockAsyncServer.toolNames() } returns tools

        assertEquals(1000, toolRegistry.getToolCount())
        assertTrue(toolRegistry.hasToolNamed("asyncTool500"))
        assertFalse(toolRegistry.hasToolNamed("asyncTool1001"))
    }

    @Test
    fun `should handle tools with unicode characters`() {
        val tools = listOf("αβγ-tool", "测试工具", "инструмент", "🔧-tool")
        every { mockAsyncServer.toolNames() } returns tools

        tools.forEach { toolName ->
            assertTrue(
                toolRegistry.hasToolNamed(toolName),
                "Unicode tool name '$toolName' should be handled correctly"
            )
        }
    }
}
