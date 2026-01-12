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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.agent.spi.support.RegistryToolGroupResolver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ToolConsumer] and related interfaces.
 */
class ToolConsumerTest {

    @Nested
    inner class ToolPublisherTest {

        @Test
        fun `invoke creates ToolPublisher with provided tools`() {
            val tool = createMockTool("published-tool")
            val publisher = ToolPublisher(listOf(tool))

            assertEquals(1, publisher.tools.size)
            assertEquals("published-tool", publisher.tools[0].definition.name)
        }

        @Test
        fun `invoke with empty list creates ToolPublisher with no tools`() {
            val publisher = ToolPublisher(emptyList())

            assertTrue(publisher.tools.isEmpty())
        }

        @Test
        fun `invoke with default creates ToolPublisher with no tools`() {
            val publisher = ToolPublisher()

            assertTrue(publisher.tools.isEmpty())
        }
    }

    @Nested
    inner class ResolveToolsTest {

        @Test
        fun `resolveTools returns direct tools when no tool groups`() {
            val tool = createMockTool("direct-tool")
            val consumer = createToolConsumer(
                name = "direct-consumer",
                tools = listOf(tool),
                toolGroups = emptySet(),
            )
            val resolver = createEmptyResolver()

            val resolved = consumer.resolveTools(resolver)

            assertEquals(1, resolved.size)
            assertEquals("direct-tool", resolved[0].definition.name)
        }

        @Test
        fun `resolveTools includes tools from resolved tool groups`() {
            val groupTool = createMockTool("group-tool")
            val toolGroup = createToolGroup("search-role", listOf(groupTool))
            val resolver = RegistryToolGroupResolver("test", listOf(toolGroup))

            val consumer = createToolConsumer(
                name = "group-consumer",
                tools = emptyList(),
                toolGroups = setOf(ToolGroupRequirement("search-role")),
            )

            val resolved = consumer.resolveTools(resolver)

            assertEquals(1, resolved.size)
            assertEquals("group-tool", resolved[0].definition.name)
        }

        @Test
        fun `resolveTools combines direct tools and group tools`() {
            val directTool = createMockTool("direct-tool")
            val groupTool = createMockTool("group-tool")
            val toolGroup = createToolGroup("combined-role", listOf(groupTool))
            val resolver = RegistryToolGroupResolver("test", listOf(toolGroup))

            val consumer = createToolConsumer(
                name = "combined-consumer",
                tools = listOf(directTool),
                toolGroups = setOf(ToolGroupRequirement("combined-role")),
            )

            val resolved = consumer.resolveTools(resolver)

            assertEquals(2, resolved.size)
            assertTrue(resolved.any { it.definition.name == "direct-tool" })
            assertTrue(resolved.any { it.definition.name == "group-tool" })
        }

        @Test
        fun `resolveTools deduplicates tools by name`() {
            val tool1 = createMockTool("duplicate-tool")
            val tool2 = createMockTool("duplicate-tool") // Same name
            val toolGroup = createToolGroup("dup-role", listOf(tool2))
            val resolver = RegistryToolGroupResolver("test", listOf(toolGroup))

            val consumer = createToolConsumer(
                name = "dedup-consumer",
                tools = listOf(tool1),
                toolGroups = setOf(ToolGroupRequirement("dup-role")),
            )

            val resolved = consumer.resolveTools(resolver)

            assertEquals(1, resolved.size)
            assertEquals("duplicate-tool", resolved[0].definition.name)
        }

        @Test
        fun `resolveTools sorts tools by name`() {
            val toolC = createMockTool("charlie")
            val toolA = createMockTool("alpha")
            val toolB = createMockTool("bravo")

            val consumer = createToolConsumer(
                name = "sorted-consumer",
                tools = listOf(toolC, toolA, toolB),
                toolGroups = emptySet(),
            )
            val resolver = createEmptyResolver()

            val resolved = consumer.resolveTools(resolver)

            assertEquals(3, resolved.size)
            assertEquals("alpha", resolved[0].definition.name)
            assertEquals("bravo", resolved[1].definition.name)
            assertEquals("charlie", resolved[2].definition.name)
        }

        @Test
        fun `resolveTools handles unresolvable tool group gracefully`() {
            val directTool = createMockTool("direct-tool")
            val resolver = createEmptyResolver() // No tool groups registered

            val consumer = createToolConsumer(
                name = "graceful-consumer",
                tools = listOf(directTool),
                toolGroups = setOf(ToolGroupRequirement("nonexistent-role")),
            )

            val resolved = consumer.resolveTools(resolver)

            // Should still return direct tools
            assertEquals(1, resolved.size)
            assertEquals("direct-tool", resolved[0].definition.name)
        }

        @Test
        fun `resolveTools handles tool group with empty tools gracefully`() {
            val directTool = createMockTool("direct-tool")
            val emptyGroup = createToolGroup("empty-role", emptyList())
            val resolver = RegistryToolGroupResolver("test", listOf(emptyGroup))

            val consumer = createToolConsumer(
                name = "empty-group-consumer",
                tools = listOf(directTool),
                toolGroups = setOf(ToolGroupRequirement("empty-role")),
            )

            val resolved = consumer.resolveTools(resolver)

            // Should still return direct tools, group has no tools to add
            assertEquals(1, resolved.size)
            assertEquals("direct-tool", resolved[0].definition.name)
        }

        @Test
        fun `resolveTools from multiple tool groups`() {
            val tool1 = createMockTool("tool-1")
            val tool2 = createMockTool("tool-2")
            val group1 = createToolGroup("role-1", listOf(tool1))
            val group2 = createToolGroup("role-2", listOf(tool2))
            val resolver = RegistryToolGroupResolver("test", listOf(group1, group2))

            val consumer = createToolConsumer(
                name = "multi-group-consumer",
                tools = emptyList(),
                toolGroups = setOf(
                    ToolGroupRequirement("role-1"),
                    ToolGroupRequirement("role-2"),
                ),
            )

            val resolved = consumer.resolveTools(resolver)

            assertEquals(2, resolved.size)
            assertTrue(resolved.any { it.definition.name == "tool-1" })
            assertTrue(resolved.any { it.definition.name == "tool-2" })
        }

        @Test
        fun `resolveTools static method works correctly`() {
            val tool = createMockTool("static-tool")
            val consumer = createToolConsumer(
                name = "static-consumer",
                tools = listOf(tool),
                toolGroups = emptySet(),
            )
            val resolver = createEmptyResolver()

            val resolved = ToolConsumer.resolveTools(consumer, resolver)

            assertEquals(1, resolved.size)
            assertEquals("static-tool", resolved[0].definition.name)
        }
    }

    @Nested
    inner class DefaultToolsTest {

        @Test
        fun `default tools implementation returns empty list`() {
            val consumer = object : ToolConsumer {
                override val name = "default-tools-consumer"
                override val toolGroups = emptySet<ToolGroupRequirement>()
            }

            assertTrue(consumer.tools.isEmpty())
        }
    }

    private fun createToolConsumer(
        name: String,
        tools: List<Tool>,
        toolGroups: Set<ToolGroupRequirement>,
    ): ToolConsumer = object : ToolConsumer {
        override val name = name
        override val tools = tools
        override val toolGroups = toolGroups
    }

    private fun createToolGroup(role: String, tools: List<Tool>): ToolGroup {
        val metadata = ToolGroupMetadata(
            description = "Test tool group for $role",
            role = role,
            name = "test-$role",
            provider = "test-provider",
            permissions = emptySet(),
        )
        return ToolGroup(metadata, tools)
    }

    private fun createEmptyResolver(): ToolGroupResolver = RegistryToolGroupResolver(
        name = "empty-resolver",
        toolGroups = emptyList(),
    )

    private fun createMockTool(name: String): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = "Mock tool $name",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = Tool.Result.text("{}")
    }
}
