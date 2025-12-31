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
package com.embabel.agent.mcpserver

import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.test.type.FunnyTool
import com.embabel.agent.test.type.PersonWithReverseTool
import com.embabel.common.util.StringTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class McpToolExportTest {

    @Nested
    inner class `fromToolObject` {

        @Test
        fun `creates export with single tool`() {
            val toolObject = ToolObject(PersonWithReverseTool("John"))
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `creates export with multiple tools from single object`() {
            val toolObject = ToolObject(
                objects = listOf(
                    PersonWithReverseTool("John"),
                    FunnyTool(),
                )
            )
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals(2, export.toolCallbacks.size)
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "reverse" })
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "thing" })
        }

        @Test
        fun `applies naming strategy to tools`() {
            val toolObject = ToolObject(
                objects = listOf(PersonWithReverseTool("John")),
                namingStrategy = { "prefix_$it" },
            )
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("prefix_reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `applies uppercase naming strategy`() {
            val toolObject = ToolObject(
                objects = listOf(PersonWithReverseTool("John")),
                namingStrategy = { it.uppercase() },
            )
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals("REVERSE", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `applies snake_case naming strategy`() {
            val toolObject = ToolObject(
                objects = listOf(FunnyTool()),
                namingStrategy = { "my_namespace_$it" },
            )
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals("my_namespace_thing", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `identity naming strategy preserves original names`() {
            val toolObject = ToolObject(
                objects = listOf(PersonWithReverseTool("John")),
                namingStrategy = StringTransformer.IDENTITY,
            )
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals("reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `applies filter to tools`() {
            val toolObject = ToolObject(
                objects = listOf(
                    PersonWithReverseTool("John"),
                    FunnyTool(),
                ),
                filter = { it == "reverse" },
            )
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `combines naming strategy and filter`() {
            val toolObject = ToolObject(
                objects = listOf(
                    PersonWithReverseTool("John"),
                    FunnyTool(),
                ),
                namingStrategy = { "ns_$it" },
                filter = { it == "thing" },
            )
            val export = McpToolExport.fromToolObject(toolObject)
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("ns_thing", export.toolCallbacks[0].toolDefinition.name())
        }
    }

    @Nested
    inner class `fromToolObjects` {

        @Test
        fun `creates export from multiple tool objects`() {
            val export = McpToolExport.fromToolObjects(
                listOf(
                    ToolObject(PersonWithReverseTool("John")),
                    ToolObject(FunnyTool()),
                )
            )
            assertEquals(2, export.toolCallbacks.size)
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "reverse" })
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "thing" })
        }

        @Test
        fun `deduplicates tools with same name`() {
            val export = McpToolExport.fromToolObjects(
                listOf(
                    ToolObject(PersonWithReverseTool("John")),
                    ToolObject(PersonWithReverseTool("Jane")),
                )
            )
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `applies different naming strategies to each tool object`() {
            val export = McpToolExport.fromToolObjects(
                listOf(
                    ToolObject(
                        objects = listOf(PersonWithReverseTool("John")),
                        namingStrategy = { "person_$it" },
                    ),
                    ToolObject(
                        objects = listOf(FunnyTool()),
                        namingStrategy = { "funny_$it" },
                    ),
                )
            )
            assertEquals(2, export.toolCallbacks.size)
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "person_reverse" })
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "funny_thing" })
        }

        @Test
        fun `handles empty list`() {
            val export = McpToolExport.fromToolObjects(emptyList())
            assertTrue(export.toolCallbacks.isEmpty())
        }

        @Test
        fun `applies additional naming strategy to all tool objects`() {
            val export = McpToolExport.fromToolObjects(
                listOf(
                    ToolObject(PersonWithReverseTool("John")),
                    ToolObject(FunnyTool()),
                ),
                namingStrategy = { "global_$it" },
            )
            assertEquals(2, export.toolCallbacks.size)
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "global_reverse" })
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "global_thing" })
        }

        @Test
        fun `composes per-object and global naming strategies`() {
            val export = McpToolExport.fromToolObjects(
                listOf(
                    ToolObject(
                        objects = listOf(PersonWithReverseTool("John")),
                        namingStrategy = { "person_$it" },
                    ),
                ),
                namingStrategy = { "${it}_v2" },
            )
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("person_reverse_v2", export.toolCallbacks[0].toolDefinition.name())
        }
    }

    @Nested
    inner class `fromLlmReference` {

        @Test
        fun `creates export from LlmReference with default naming`() {
            val reference = TestLlmReference(name = "testref", description = "A test reference")
            val export = McpToolExport.fromLlmReference(reference)
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("testref_reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `reference naming strategy lowercases tool prefix`() {
            val reference = TestLlmReference(name = "API", description = "An API")
            val export = McpToolExport.fromLlmReference(reference)
            assertEquals("api_reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `reference with special chars in name normalizes prefix`() {
            val reference = TestLlmReference(name = "My-API.v2", description = "API with special chars")
            val export = McpToolExport.fromLlmReference(reference)
            assertEquals("my_api_v2_reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `reference with spaces preserves spaces in prefix`() {
            val reference = TestLlmReference(name = "My API", description = "API with spaces")
            val export = McpToolExport.fromLlmReference(reference)
            assertEquals("my api_reverse", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `applies additional naming strategy on top of reference strategy`() {
            val reference = TestLlmReference(name = "api", description = "An API")
            val export = McpToolExport.fromLlmReference(reference) { "${it}_v1" }
            assertEquals("api_reverse_v1", export.toolCallbacks[0].toolDefinition.name())
        }

        @Test
        fun `composes reference and additional naming strategies`() {
            val reference = TestLlmReference(name = "weather", description = "Weather API")
            val export = McpToolExport.fromLlmReference(reference) { it.uppercase() }
            assertEquals("WEATHER_REVERSE", export.toolCallbacks[0].toolDefinition.name())
        }
    }

    @Nested
    inner class `fromLlmReferences` {

        @Test
        fun `creates export from multiple LlmReferences`() {
            val export = McpToolExport.fromLlmReferences(
                listOf(
                    TestLlmReference(name = "ref1", description = "First"),
                    TestLlmReferenceWithFunnyTool(name = "ref2", description = "Second"),
                )
            )
            assertEquals(2, export.toolCallbacks.size)
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "ref1_reverse" })
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "ref2_thing" })
        }

        @Test
        fun `handles empty list of references`() {
            val export = McpToolExport.fromLlmReferences(emptyList())
            assertTrue(export.toolCallbacks.isEmpty())
        }

        @Test
        fun `applies additional naming strategy to all references`() {
            val export = McpToolExport.fromLlmReferences(
                listOf(
                    TestLlmReference(name = "ref1", description = "First"),
                    TestLlmReferenceWithFunnyTool(name = "ref2", description = "Second"),
                ),
                namingStrategy = { "${it}_prod" },
            )
            assertEquals(2, export.toolCallbacks.size)
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "ref1_reverse_prod" })
            assertTrue(export.toolCallbacks.any { it.toolDefinition.name() == "ref2_thing_prod" })
        }

        @Test
        fun `composes reference and global naming strategies`() {
            val export = McpToolExport.fromLlmReferences(
                listOf(TestLlmReference(name = "api", description = "API")),
                namingStrategy = { it.uppercase() },
            )
            assertEquals(1, export.toolCallbacks.size)
            assertEquals("API_REVERSE", export.toolCallbacks[0].toolDefinition.name())
        }
    }

    @Nested
    inner class `infoString` {

        @Test
        fun `returns descriptive info string`() {
            val export = McpToolExport.fromToolObject(ToolObject(PersonWithReverseTool("John")))
            val info = export.infoString(verbose = false, indent = 0)
            assertTrue(info.contains("1 tool callbacks"))
        }

        @Test
        fun `info string reflects tool count`() {
            val export = McpToolExport.fromToolObjects(
                listOf(
                    ToolObject(PersonWithReverseTool("John")),
                    ToolObject(FunnyTool()),
                )
            )
            val info = export.infoString(verbose = true, indent = 2)
            assertTrue(info.contains("2 tool callbacks"))
        }
    }

    @Nested
    inner class `tool invocation with logging` {

        @Test
        fun `decorated tool callback can be invoked`() {
            val export = McpToolExport.fromToolObject(ToolObject(PersonWithReverseTool("hello")))
            val reverseTool = export.toolCallbacks.first { it.toolDefinition.name() == "reverse" }
            val result = reverseTool.call("{}")
            assertEquals("\"olleh\"", result)
        }
    }

    private class TestLlmReference(
        override val name: String,
        override val description: String,
    ) : LlmReference {
        override fun notes(): String = "Test notes"
        override fun toolInstances(): List<Any> = listOf(PersonWithReverseTool("test"))
    }

    private class TestLlmReferenceWithFunnyTool(
        override val name: String,
        override val description: String,
    ) : LlmReference {
        override fun notes(): String = "Test notes"
        override fun toolInstances(): List<Any> = listOf(FunnyTool())
    }
}
