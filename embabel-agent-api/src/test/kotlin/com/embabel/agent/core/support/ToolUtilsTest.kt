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
package com.embabel.agent.core.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.common.util.StringTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory

class ToolUtilsTest {

    @Nested
    inner class RenamedToolTest {

        @Test
        fun `RenamedTool changes tool name`() {
            val original = createMockTool("original-name") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals("new-name", renamed.definition.name)
        }

        @Test
        fun `RenamedTool preserves description`() {
            val original = createMockTool("tool", "Original description") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals("Original description", renamed.definition.description)
        }

        @Test
        fun `RenamedTool preserves input schema`() {
            val original = createMockTool("tool") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals(original.definition.inputSchema, renamed.definition.inputSchema)
        }

        @Test
        fun `RenamedTool preserves metadata`() {
            val original = createMockTool("tool") { Tool.Result.text("result") }
            val renamed = RenamedTool(original, "new-name")

            assertEquals(original.metadata, renamed.metadata)
        }

        @Test
        fun `RenamedTool delegates call to original`() {
            var callInput: String? = null
            val original = createMockTool("tool") { input ->
                callInput = input
                Tool.Result.text("delegated result")
            }
            val renamed = RenamedTool(original, "new-name")

            val result = renamed.call("""{"key": "value"}""")

            assertEquals("""{"key": "value"}""", callInput)
            assertEquals("delegated result", (result as Tool.Result.Text).content)
        }
    }

    @Nested
    inner class SafelyGetToolsTest {

        @Test
        fun `safelyGetTools returns empty list for empty input`() {
            val tools = safelyGetTools(emptyList())

            assertTrue(tools.isEmpty())
        }

        @Test
        fun `safelyGetTools extracts Tool instances directly`() {
            val tool = createMockTool("direct-tool") { Tool.Result.text("{}") }
            val toolObject = ToolObject.from(tool)

            val tools = safelyGetTools(listOf(toolObject))

            assertEquals(1, tools.size)
            assertEquals("direct-tool", tools[0].definition.name)
        }

        @Test
        fun `safelyGetTools deduplicates by tool name`() {
            val tool1 = createMockTool("duplicate-name") { Tool.Result.text("1") }
            val tool2 = createMockTool("duplicate-name") { Tool.Result.text("2") }
            val toolObject = ToolObject(listOf(tool1, tool2))

            val tools = safelyGetTools(listOf(toolObject))

            assertEquals(1, tools.size)
            assertEquals("duplicate-name", tools[0].definition.name)
        }

        @Test
        fun `safelyGetTools sorts tools by name`() {
            val toolC = createMockTool("charlie") { Tool.Result.text("{}") }
            val toolA = createMockTool("alpha") { Tool.Result.text("{}") }
            val toolB = createMockTool("bravo") { Tool.Result.text("{}") }
            val toolObject = ToolObject(listOf(toolC, toolA, toolB))

            val tools = safelyGetTools(listOf(toolObject))

            assertEquals(3, tools.size)
            assertEquals("alpha", tools[0].definition.name)
            assertEquals("bravo", tools[1].definition.name)
            assertEquals("charlie", tools[2].definition.name)
        }
    }

    @Nested
    inner class SafelyGetToolsFromTest {

        @Test
        fun `safelyGetToolsFrom extracts Tool instance`() {
            val tool = createMockTool("my-tool") { Tool.Result.text("{}") }
            val toolObject = ToolObject.from(tool)

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("my-tool", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom applies filter`() {
            val tool1 = createMockTool("include-me") { Tool.Result.text("{}") }
            val tool2 = createMockTool("exclude-me") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool1, tool2),
                filter = { name -> name.startsWith("include") },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("include-me", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom applies naming strategy`() {
            val tool = createMockTool("original-name") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool),
                namingStrategy = StringTransformer { "renamed-$it" },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("renamed-original-name", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom does not rename if name unchanged`() {
            val tool = createMockTool("same-name") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool),
                namingStrategy = StringTransformer { it },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertEquals("same-name", tools[0].definition.name)
            assertFalse(tools[0] is RenamedTool)
        }

        @Test
        fun `safelyGetToolsFrom wraps renamed tools in RenamedTool`() {
            val tool = createMockTool("original") { Tool.Result.text("{}") }
            val toolObject = ToolObject(
                objects = listOf(tool),
                namingStrategy = StringTransformer { "prefix-$it" },
            )

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
            assertTrue(tools[0] is RenamedTool)
            assertEquals("prefix-original", tools[0].definition.name)
        }

        @Test
        fun `safelyGetToolsFrom deduplicates tools`() {
            val tool1 = createMockTool("same") { Tool.Result.text("1") }
            val tool2 = createMockTool("same") { Tool.Result.text("2") }
            val toolObject = ToolObject(listOf(tool1, tool2))

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals(1, tools.size)
        }

        @Test
        fun `safelyGetToolsFrom sorts tools by name`() {
            val toolZ = createMockTool("zulu") { Tool.Result.text("{}") }
            val toolA = createMockTool("alpha") { Tool.Result.text("{}") }
            val toolObject = ToolObject(listOf(toolZ, toolA))

            val tools = safelyGetToolsFrom(toolObject)

            assertEquals("alpha", tools[0].definition.name)
            assertEquals("zulu", tools[1].definition.name)
        }
    }

    @Nested
    inner class NameCollisionWarningTest {

        @Test
        fun `safelyGetToolsFrom warns with class and method when two different tools have the same name`() {
            val warnings = captureWarnings("com.embabel.agent.core.support.ToolUtils") {
                safelyGetToolsFrom(ToolObject(listOf(LookupToolsA(), LookupToolsB())))
            }

            assertEquals(1, warnings.size, "warnings were $warnings")
            assertTrue(warnings[0].contains("'lookup'"), warnings[0])
            assertTrue(warnings[0].contains("LookupToolsA.lookup"), warnings[0])
            assertTrue(warnings[0].contains("LookupToolsB.lookup"), warnings[0])
        }

        @Test
        fun `safelyGetTools warns with class and method when tool objects collide`() {
            val warnings = captureWarnings("com.embabel.agent.core.support.ToolUtils") {
                safelyGetTools(listOf(ToolObject(LookupToolsA()), ToolObject(LookupToolsB())))
            }

            assertEquals(1, warnings.size, "warnings were $warnings")
            assertTrue(warnings[0].contains("LookupToolsA.lookup"), warnings[0])
            assertTrue(warnings[0].contains("LookupToolsB.lookup"), warnings[0])
        }

        @Test
        fun `source description shows the real class of a Spring proxy`() {
            val proxy = ProxyFactory(ProxiedLookupTools()).apply { isProxyTargetClass = true }.proxy
            val tool = Tool.fromInstance(proxy).single()

            assertEquals("${ProxiedLookupTools::class.java.name}.lookup", tool.sourceDescription())
        }

        @Test
        fun `does not warn when the same object is added two times`() {
            val tools = LookupToolsA()

            val warnings = captureWarnings("com.embabel.agent.core.support.ToolUtils") {
                safelyGetTools(listOf(ToolObject(tools), ToolObject(tools)))
            }

            assertTrue(warnings.isEmpty(), "warnings were $warnings")
        }
    }

    private fun createMockTool(
        name: String,
        description: String = "Mock tool $name",
        onCall: (String) -> Tool.Result,
    ): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = description,
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = onCall(input)
    }
}
