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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.spi.common.Constants.EMBABEL_PROVIDER
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

class ToolGroupsConfigurationTest {

    // -------------------------------------------------------------------------
    // GroupConfig
    // -------------------------------------------------------------------------

    @Nested
    inner class GroupConfigDefaultsTest {

        @Test
        fun `description defaults to null`() {
            assertNull(GroupConfig().description)
        }

        @Test
        fun `provider defaults to EMBABEL_PROVIDER`() {
            assertEquals(EMBABEL_PROVIDER, GroupConfig().provider)
        }

        @Test
        fun `tools defaults to empty set`() {
            assertTrue(GroupConfig().tools.isEmpty())
        }
    }

    @Nested
    inner class GroupConfigIncludeTest {

        private fun toolWithName(name: String): ToolCallback {
            val def = mockk<ToolDefinition>()
            every { def.name() } returns name
            val tool = mockk<ToolCallback>()
            every { tool.toolDefinition } returns def
            return tool
        }

        @Test
        fun `include returns true when tool name ends with configured suffix`() {
            val config = GroupConfig(tools = setOf("search_brave"))
            assertTrue(config.include(toolWithName("mcp_search_brave")))
        }

        @Test
        fun `include returns false when tool name does not match any suffix`() {
            val config = GroupConfig(tools = setOf("search_brave"))
            assertFalse(config.include(toolWithName("mcp_get_weather")))
        }

        @Test
        fun `include returns false when tools set is empty`() {
            val config = GroupConfig(tools = emptySet())
            assertFalse(config.include(toolWithName("any_tool")))
        }

        @Test
        fun `include returns true when any configured suffix matches`() {
            val config = GroupConfig(tools = setOf("search_brave", "fetch", "get_summary"))
            assertTrue(config.include(toolWithName("wikipedia_get_summary")))
        }

        @Test
        fun `include is suffix match - does not match substring in the middle`() {
            val config = GroupConfig(tools = setOf("brave"))
            // "brave_search" does NOT end with "brave"
            assertFalse(config.include(toolWithName("brave_search")))
        }

        @Test
        fun `include matches exact name when tool name equals suffix`() {
            val config = GroupConfig(tools = setOf("my_tool"))
            assertTrue(config.include(toolWithName("my_tool")))
        }
    }

    // -------------------------------------------------------------------------
    // ToolGroupsProperties
    // -------------------------------------------------------------------------

    @Nested
    inner class ToolGroupsPropertiesTest {

        @Test
        fun `lazyInit defaults to false`() {
            assertFalse(ToolGroupsProperties().lazyInit)
        }

        @Test
        fun `includes defaults to empty map`() {
            assertTrue(ToolGroupsProperties().includes.isEmpty())
        }

        @Test
        fun `excludes defaults to empty list`() {
            assertTrue(ToolGroupsProperties().excludes.isEmpty())
        }

        @Test
        fun `lazyInit can be set to true`() {
            val props = ToolGroupsProperties()
            props.lazyInit = true
            assertTrue(props.lazyInit)
        }

        @Test
        fun `includes can be populated`() {
            val props = ToolGroupsProperties()
            props.includes = mapOf("web" to GroupConfig(description = "web tools"))
            assertEquals(1, props.includes.size)
            assertEquals("web tools", props.includes["web"]?.description)
        }
    }
}
