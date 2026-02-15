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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.tools.mcp.McpToolFactory
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.client.McpSyncClient
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpringAiMcpToolFactoryTest {

    private val factory: McpToolFactory = SpringAiMcpToolFactory(emptyList())

    @Nested
    inner class `implements McpToolFactory` {
        @Test
        fun `is instance of McpToolFactory`() {
            assertIs<McpToolFactory>(factory)
        }
    }

    @Nested
    inner class `unfolding` {
        @Test
        fun `creates empty unfolding tool with no clients`() {
            val tool = factory.unfolding(
                name = "test",
                description = "Test tool",
                filter = { true },
                removeOnInvoke = true,
            )
            assertNotNull(tool)
            assertEquals("test", tool.definition.name)
        }

        @Test
        fun `default removeOnInvoke overload works`() {
            val tool = factory.unfolding(
                name = "test",
                description = "Test tool",
                filter = { true },
            )
            assertNotNull(tool)
        }
    }

    @Nested
    inner class `unfoldingByName` {
        @Test
        fun `creates tool with name filter`() {
            val tool = factory.unfoldingByName(
                name = "test",
                description = "Test tool",
                toolNames = setOf("tool1", "tool2"),
                removeOnInvoke = false,
            )
            assertNotNull(tool)
        }

        @Test
        fun `default removeOnInvoke overload works`() {
            val tool = factory.unfoldingByName(
                name = "test",
                description = "Test tool",
                toolNames = setOf("tool1"),
            )
            assertNotNull(tool)
        }
    }

    @Nested
    inner class `unfoldingMatching` {
        @Test
        fun `creates tool with pattern filter`() {
            val tool = factory.unfoldingMatching(
                name = "test",
                description = "Test tool",
                patterns = listOf("^test_".toRegex()),
                removeOnInvoke = true,
            )
            assertNotNull(tool)
        }

        @Test
        fun `default removeOnInvoke overload works`() {
            val tool = factory.unfoldingMatching(
                name = "test",
                description = "Test tool",
                patterns = listOf(".*".toRegex()),
            )
            assertNotNull(tool)
        }
    }

    @Nested
    inner class `toolByName` {
        @Test
        fun `returns null when no clients`() {
            assertNull(factory.toolByName("nonexistent"))
        }
    }

    @Nested
    inner class `requiredToolByName` {
        @Test
        fun `throws when tool not found`() {
            assertThrows<IllegalArgumentException> {
                factory.requiredToolByName("nonexistent")
            }
        }
    }
}
