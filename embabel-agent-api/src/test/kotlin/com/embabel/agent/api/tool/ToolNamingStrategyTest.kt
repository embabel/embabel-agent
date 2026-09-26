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
package com.embabel.agent.api.tool


import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ToolNamingStrategyTest {

    @Nested
    inner class LegacyNameOnly {

        @Test
        fun `keeps the existing tool name`() {
            val name = ToolNamingStrategy.LEGACY_NAME_ONLY.nameFor("Agent", "lookup")

            assertEquals("lookup", name)
        }

        @Test
        fun `keeps the existing tool name without an owner`() {
            val name = ToolNamingStrategy.LEGACY_NAME_ONLY.nameFor(null, "lookup")

            assertEquals("lookup", name)
        }

        @Test
        fun `rejects invalid tool names`() {
            assertFailsWith<IllegalArgumentException> {
                ToolNamingStrategy.LEGACY_NAME_ONLY.nameFor(null, "lookup.1")
            }
        }
    }

    @Nested
    inner class FullyQualified {

        @Test
        fun `qualifies the tool name with its owner`() {
            val name = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("AgentA", "search")

            assertEquals("AgentA-search", name)
        }

        @Test
        fun `leaves the tool name unqualified without an owner`() {
            assertEquals("search", ToolNamingStrategy.FULLY_QUALIFIED.nameFor(null, "search"))
            assertEquals("search", ToolNamingStrategy.FULLY_QUALIFIED.nameFor("  ", "search"))
        }

        @Test
        fun `allows hyphens and underscores in tool names`() {
            val name = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent", "lookup-tool_v1")

            assertEquals("Agent-lookup-tool_v1", name)
        }

        @Test
        fun `escapes owner characters to protect the join delimiter`() {
            val name = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent-1", "lookup")

            assertEquals("Agent_2d_1-lookup", name)
        }

        @Test
        fun `rejects tool names with invalid characters`() {
            assertFailsWith<IllegalArgumentException> {
                ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent", "lookup.1")
            }
            assertFailsWith<IllegalArgumentException> {
                ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent", "lookup tool")
            }
        }

        @Test
        fun `rejects published names exceeding maximum length`() {
            val owner = "VeryLongAgentName".repeat(4)
            assertFailsWith<IllegalArgumentException> {
                ToolNamingStrategy.FULLY_QUALIFIED.nameFor(owner, "first")
            }
        }
    }

}
