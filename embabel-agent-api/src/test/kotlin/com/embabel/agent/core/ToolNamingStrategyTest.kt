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
package com.embabel.agent.core


import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
        fun `encodes characters that would otherwise break the join`() {
            val name = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent", "lookup-tool")

            assertEquals("Agent-lookup_2d_tool", name)
        }

        @Test
        fun `encodes unsafe characters without collapsing distinct names`() {
            val hyphenated = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent", "lookup-1")
            val dotted = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent", "lookup.1")

            assertNotEquals(hyphenated, dotted)
        }

        @Test
        fun `keeps distinct owner and tool pairs distinct when text mimics an encoding`() {
            val dottedOwner = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent.2e", "x")
            val dottedTool = ToolNamingStrategy.FULLY_QUALIFIED.nameFor("Agent", "2e.x")

            assertNotEquals(dottedOwner, dottedTool)
        }

        @Test
        fun `bounds long names without collapsing distinct names`() {
            val owner = "VeryLongAgentName".repeat(4)
            val first = ToolNamingStrategy.FULLY_QUALIFIED.nameFor(owner, "first")
            val second = ToolNamingStrategy.FULLY_QUALIFIED.nameFor(owner, "second")

            assertEquals(64, first.length)
            assertEquals(64, second.length)
            assertNotEquals(first, second)
        }
    }

}
