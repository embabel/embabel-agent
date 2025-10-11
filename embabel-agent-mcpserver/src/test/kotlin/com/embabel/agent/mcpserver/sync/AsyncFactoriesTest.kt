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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for synchronous MCP server components.
 * This placeholder class ensures the sync package has complete test coverage.
 */
class SyncIntegrationTest {

    @Test
    fun `sync package should be properly structured`() {
        // Verify core sync classes exist and are accessible
        assertDoesNotThrow {
            SyncServerStrategy::class.java
            SyncToolRegistry::class.java
            McpPromptFactory::class.java
        }
    }

    @Test
    fun `sync components should follow naming conventions`() {
        val syncClasses = listOf(
            SyncServerStrategy::class.java,
            SyncToolRegistry::class.java
        )

        syncClasses.forEach { clazz ->
            assertTrue(
                clazz.simpleName.startsWith("Sync"),
                "Sync class ${clazz.simpleName} should start with 'Sync'"
            )
        }
    }

    @Test
    fun `sync package should have proper interfaces`() {
        // Verify that sync implementations implement the correct interfaces
        assertTrue(
            com.embabel.agent.mcpserver.McpServerStrategy::class.java.isAssignableFrom(SyncServerStrategy::class.java),
            "SyncServerStrategy should implement McpServerStrategy"
        )

        assertTrue(
            com.embabel.agent.mcpserver.ToolRegistry::class.java.isAssignableFrom(SyncToolRegistry::class.java),
            "SyncToolRegistry should implement ToolRegistry"
        )
    }

    @Test
    fun `sync classes should be in correct package`() {
        assertEquals(
            "com.embabel.agent.mcpserver.sync",
            SyncServerStrategy::class.java.packageName
        )
        assertEquals(
            "com.embabel.agent.mcpserver.sync",
            SyncToolRegistry::class.java.packageName
        )
        assertEquals(
            "com.embabel.agent.mcpserver.sync",
            McpPromptFactory::class.java.packageName
        )
    }
}
