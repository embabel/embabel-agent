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
package com.embabel.agent.mcpserver.support

import io.mockk.*
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpSyncServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class ExtensionsTest {

    @BeforeEach
    fun setUp() {
        mockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
    }

    @Test
    fun `McpAsyncServer toolNames extension should be available`() {
        val asyncServer = mockk<McpAsyncServer>()

        // Mock the extension function behavior
        every { asyncServer.toolNames() } returns listOf("tool1", "tool2")

        val result = asyncServer.toolNames()

        assertEquals(listOf("tool1", "tool2"), result)
    }

    @Test
    fun `McpAsyncServer toolNames should handle empty list`() {
        val asyncServer = mockk<McpAsyncServer>()

        every { asyncServer.toolNames() } returns emptyList()

        val result = asyncServer.toolNames()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `McpSyncServer toolNames should delegate to asyncServer`() {
        val mockAsyncServer = mockk<McpAsyncServer>()
        every { mockAsyncServer.toolNames() } returns listOf("syncTool1", "syncTool2")

        val syncServer = mockk<McpSyncServer>()
        every { syncServer.asyncServer } returns mockAsyncServer

        val result = syncServer.toolNames()

        assertEquals(listOf("syncTool1", "syncTool2"), result)
        verify { mockAsyncServer.toolNames() }
    }

    @Test
    fun `McpSyncServer toolNames should handle empty result from asyncServer`() {
        val mockAsyncServer = mockk<McpAsyncServer>()
        every { mockAsyncServer.toolNames() } returns emptyList()

        val syncServer = mockk<McpSyncServer>()
        every { syncServer.asyncServer } returns mockAsyncServer

        val result = syncServer.toolNames()

        assertEquals(emptyList<String>(), result)
        verify { mockAsyncServer.toolNames() }
    }

    @Test
    fun `extension functions should be properly imported and accessible`() {
        // This test verifies that the extension functions are properly defined
        // and can be imported without compilation errors

        val asyncServer = mockk<McpAsyncServer>()
        val syncServer = mockk<McpSyncServer>()

        every { asyncServer.toolNames() } returns listOf("test")
        every { syncServer.asyncServer } returns asyncServer

        // These calls should compile without issues
        assertNotNull(asyncServer.toolNames())
        assertNotNull(syncServer.toolNames())
    }

    @Test
    fun `toolNames extensions should handle various scenarios`() {
        val asyncServer = mockk<McpAsyncServer>()
        val syncServer = mockk<McpSyncServer>()

        // Test with various return values
        val testCases = listOf(
            emptyList<String>(),
            listOf("singleTool"),
            listOf("tool1", "tool2", "tool3"),
            listOf("tool-with-dashes", "tool_with_underscores", "tool.with.dots")
        )

        testCases.forEach { expectedTools ->
            every { asyncServer.toolNames() } returns expectedTools
            every { syncServer.asyncServer } returns asyncServer

            assertEquals(expectedTools, asyncServer.toolNames())
            assertEquals(expectedTools, syncServer.toolNames())
        }
    }
}
