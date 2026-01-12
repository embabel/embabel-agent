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
package com.embabel.agent.spi.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.ToolGroupRequirement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [RegistryToolGroupResolver].
 */
class RegistryToolGroupResolverTest {

    @Nested
    inner class ResolveToolGroupTest {

        @Test
        fun `resolveToolGroup returns tool group when role matches`() {
            val toolGroup = createToolGroup("search-role", listOf("search-tool"))
            val resolver = RegistryToolGroupResolver(
                name = "test-resolver",
                toolGroups = listOf(toolGroup),
            )

            val resolution = resolver.resolveToolGroup(ToolGroupRequirement("search-role"))

            assertNotNull(resolution.resolvedToolGroup)
            assertNull(resolution.failureMessage)
            assertEquals("search-role", resolution.resolvedToolGroup!!.metadata.role)
        }

        @Test
        fun `resolveToolGroup returns failure when role not found`() {
            val toolGroup = createToolGroup("existing-role", emptyList())
            val resolver = RegistryToolGroupResolver(
                name = "test-resolver",
                toolGroups = listOf(toolGroup),
            )

            val resolution = resolver.resolveToolGroup(ToolGroupRequirement("nonexistent-role"))

            assertNull(resolution.resolvedToolGroup)
            assertNotNull(resolution.failureMessage)
            assertTrue(resolution.failureMessage!!.contains("nonexistent-role"))
        }

        @Test
        fun `resolveToolGroup returns first matching tool group when multiple have same role`() {
            val toolGroup1 = createToolGroup("duplicate-role", listOf("tool-1"))
            val toolGroup2 = createToolGroup("duplicate-role", listOf("tool-2"))
            val resolver = RegistryToolGroupResolver(
                name = "test-resolver",
                toolGroups = listOf(toolGroup1, toolGroup2),
            )

            val resolution = resolver.resolveToolGroup(ToolGroupRequirement("duplicate-role"))

            assertNotNull(resolution.resolvedToolGroup)
            // Should return first matching
            assertEquals(1, resolution.resolvedToolGroup!!.tools.size)
            assertEquals("tool-1", resolution.resolvedToolGroup!!.tools[0].definition.name)
        }

        @Test
        fun `resolveToolGroup with empty registry returns failure`() {
            val resolver = RegistryToolGroupResolver(
                name = "empty-resolver",
                toolGroups = emptyList(),
            )

            val resolution = resolver.resolveToolGroup(ToolGroupRequirement("any-role"))

            assertNull(resolution.resolvedToolGroup)
            assertNotNull(resolution.failureMessage)
        }
    }

    @Nested
    inner class FindToolGroupForToolTest {

        @Test
        fun `findToolGroupForTool returns group containing the tool`() {
            val toolGroup = createToolGroup("tool-role", listOf("target-tool", "other-tool"))
            val resolver = RegistryToolGroupResolver(
                name = "test-resolver",
                toolGroups = listOf(toolGroup),
            )

            val resolution = resolver.findToolGroupForTool("target-tool")

            assertNotNull(resolution.resolvedToolGroup)
            assertNull(resolution.failureMessage)
            assertEquals("tool-role", resolution.resolvedToolGroup!!.metadata.role)
        }

        @Test
        fun `findToolGroupForTool returns failure when tool not found`() {
            val toolGroup = createToolGroup("tool-role", listOf("existing-tool"))
            val resolver = RegistryToolGroupResolver(
                name = "test-resolver",
                toolGroups = listOf(toolGroup),
            )

            val resolution = resolver.findToolGroupForTool("nonexistent-tool")

            assertNull(resolution.resolvedToolGroup)
            assertNotNull(resolution.failureMessage)
            assertTrue(resolution.failureMessage!!.contains("nonexistent-tool"))
        }

        @Test
        fun `findToolGroupForTool searches across multiple groups`() {
            val group1 = createToolGroup("group-1", listOf("tool-a", "tool-b"))
            val group2 = createToolGroup("group-2", listOf("tool-c", "tool-d"))
            val resolver = RegistryToolGroupResolver(
                name = "test-resolver",
                toolGroups = listOf(group1, group2),
            )

            val resolution = resolver.findToolGroupForTool("tool-c")

            assertNotNull(resolution.resolvedToolGroup)
            assertEquals("group-2", resolution.resolvedToolGroup!!.metadata.role)
        }

        @Test
        fun `findToolGroupForTool with empty registry returns failure`() {
            val resolver = RegistryToolGroupResolver(
                name = "empty-resolver",
                toolGroups = emptyList(),
            )

            val resolution = resolver.findToolGroupForTool("any-tool")

            assertNull(resolution.resolvedToolGroup)
            assertNotNull(resolution.failureMessage)
        }
    }

    @Nested
    inner class AvailableToolGroupsTest {

        @Test
        fun `availableToolGroups returns metadata for all groups`() {
            val group1 = createToolGroup("role-1", emptyList())
            val group2 = createToolGroup("role-2", emptyList())
            val resolver = RegistryToolGroupResolver(
                name = "test-resolver",
                toolGroups = listOf(group1, group2),
            )

            val available = resolver.availableToolGroups()

            assertEquals(2, available.size)
            assertTrue(available.any { it.role == "role-1" })
            assertTrue(available.any { it.role == "role-2" })
        }

        @Test
        fun `availableToolGroups with empty registry returns empty list`() {
            val resolver = RegistryToolGroupResolver(
                name = "empty-resolver",
                toolGroups = emptyList(),
            )

            val available = resolver.availableToolGroups()

            assertTrue(available.isEmpty())
        }
    }

    @Nested
    inner class InfoStringTest {

        @Test
        fun `toString includes name and tool group count`() {
            val group1 = createToolGroup("role-1", emptyList())
            val group2 = createToolGroup("role-2", emptyList())
            val resolver = RegistryToolGroupResolver(
                name = "my-resolver",
                toolGroups = listOf(group1, group2),
            )

            val str = resolver.toString()

            assertTrue(str.contains("my-resolver"))
            assertTrue(str.contains("2"))
            assertTrue(str.contains("role-1"))
            assertTrue(str.contains("role-2"))
        }

        @Test
        fun `infoString verbose false returns concise string`() {
            val group = createToolGroup("test-role", emptyList())
            val resolver = RegistryToolGroupResolver(
                name = "my-resolver",
                toolGroups = listOf(group),
            )

            val info = resolver.infoString(verbose = false)

            assertTrue(info.contains("my-resolver"))
            assertTrue(info.contains("1"))
        }

        @Test
        fun `infoString verbose true returns detailed string`() {
            val group = createToolGroup("detailed-role", listOf("detailed-tool"))
            val resolver = RegistryToolGroupResolver(
                name = "verbose-resolver",
                toolGroups = listOf(group),
            )

            val info = resolver.infoString(verbose = true)

            assertTrue(info.contains("verbose-resolver"))
            assertTrue(info.contains("detailed-role"))
        }

        @Test
        fun `infoString sorts groups by role`() {
            val groupC = createToolGroup("charlie", emptyList())
            val groupA = createToolGroup("alpha", emptyList())
            val groupB = createToolGroup("bravo", emptyList())
            val resolver = RegistryToolGroupResolver(
                name = "sorted-resolver",
                toolGroups = listOf(groupC, groupA, groupB),
            )

            val info = resolver.infoString(verbose = true)

            val alphaIndex = info.indexOf("alpha")
            val bravoIndex = info.indexOf("bravo")
            val charlieIndex = info.indexOf("charlie")

            assertTrue(alphaIndex < bravoIndex)
            assertTrue(bravoIndex < charlieIndex)
        }
    }

    @Nested
    inner class NamePropertyTest {

        @Test
        fun `name property returns resolver name`() {
            val resolver = RegistryToolGroupResolver(
                name = "named-resolver",
                toolGroups = emptyList(),
            )

            assertEquals("named-resolver", resolver.name)
        }
    }

    private fun createToolGroup(role: String, toolNames: List<String>): ToolGroup {
        val metadata = ToolGroupMetadata(
            description = "Test tool group for $role",
            role = role,
            name = "test-$role",
            provider = "test-provider",
            permissions = emptySet(),
        )
        val tools = toolNames.map { createMockTool(it) }
        return ToolGroup(metadata, tools)
    }

    private fun createMockTool(name: String): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = "Mock tool $name",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = Tool.Result.text("{}")
    }
}
