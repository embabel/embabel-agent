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
package com.embabel.agent.tools.mcp

import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupPermission
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class McpToolGroupTest {

    private fun createGroup(
        clients: List<McpSyncClient> = emptyList(),
        filter: (org.springframework.ai.tool.ToolCallback) -> Boolean = { true },
    ) = McpToolGroup(
        description = ToolGroupDescription("Test tool group", "test-role"),
        provider = "test-provider",
        name = "test-group",
        permissions = setOf(ToolGroupPermission.INTERNET_ACCESS),
        clients = clients,
        filter = filter,
    )

    @Nested
    inner class MetadataTest {

        @Test
        fun `metadata is eagerly populated at construction`() {
            val group = createGroup()
            assertEquals("test-role", group.metadata.role)
        }

        @Test
        fun `metadata name matches constructor arg`() {
            val group = createGroup()
            assertEquals("test-group", group.metadata.name)
        }

        @Test
        fun `metadata provider matches constructor arg`() {
            val group = createGroup()
            assertEquals("test-provider", group.metadata.provider)
        }

        @Test
        fun `metadata permissions matches constructor arg`() {
            val group = createGroup()
            assertEquals(setOf(ToolGroupPermission.INTERNET_ACCESS), group.metadata.permissions)
        }

        @Test
        fun `metadata description matches constructor arg`() {
            val group = createGroup()
            assertEquals("Test tool group", group.metadata.description)
        }
    }

    @Nested
    inner class LazyInitializationTest {

        @Test
        fun `tools delegate is not initialized at construction`() {
            val group = createGroup()
            assertTrue(group.toString().contains("toolsInitialized=false"))
        }

        @Test
        fun `no client methods are called at construction`() {
            val strictClient = mockk<McpSyncClient>(relaxed = false)
            // Construction must not invoke any methods on the client.
            // If it does, MockK will throw because no expectations are set.
            assertDoesNotThrow { createGroup(clients = listOf(strictClient)) }
        }

        @Test
        fun `toString shows toolsInitialized=true after tools are accessed`() {
            val group = createGroup()
            group.tools
            assertTrue(group.toString().contains("toolsInitialized=true"))
        }

        @Test
        fun `toString contains metadata role`() {
            val group = createGroup()
            assertTrue(group.toString().contains("test-role"))
        }
    }

    @Nested
    inner class ToolLoadingTest {

        @Test
        fun `returns empty list when no clients are configured`() {
            val group = createGroup(clients = emptyList())
            assertTrue(group.tools.isEmpty())
        }

        @Test
        fun `returns empty list gracefully when MCP client throws`() {
            val failingClient = mockk<McpSyncClient>()
            every { failingClient.listTools() } throws RuntimeException("connection refused")

            val group = createGroup(clients = listOf(failingClient))

            assertDoesNotThrow { group.tools }
            assertTrue(group.tools.isEmpty())
        }

        @Test
        fun `tools result is cached - client called only once despite repeated accesses`() {
            val failingClient = mockk<McpSyncClient>()
            every { failingClient.listTools() } throws RuntimeException("connection refused")

            val group = createGroup(clients = listOf(failingClient))

            // Access tools three times — loadTools() should fire only on the first
            group.tools
            group.tools
            group.tools

            verify(exactly = 1) { failingClient.listTools() }
        }

        @Test
        fun `repeated tools accesses return the same list instance`() {
            val group = createGroup(clients = emptyList())
            val first = group.tools
            val second = group.tools
            assertSame(first, second)
        }
    }
}
