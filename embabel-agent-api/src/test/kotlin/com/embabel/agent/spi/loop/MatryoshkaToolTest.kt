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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.chat.UserMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MatryoshkaToolTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class MatryoshkaToolCreationTest {

        @Test
        fun `of creates tool with inner tools`() {
            val innerTool1 = MockTool("inner1", "Inner tool 1") { Tool.Result.text("1") }
            val innerTool2 = MockTool("inner2", "Inner tool 2") { Tool.Result.text("2") }

            val matryoshka = MatryoshkaTool.of(
                name = "category",
                description = "A category of tools",
                innerTools = listOf(innerTool1, innerTool2),
            )

            assertEquals("category", matryoshka.definition.name)
            assertEquals("A category of tools", matryoshka.definition.description)
            assertEquals(2, matryoshka.innerTools.size)
            assertTrue(matryoshka.removeOnInvoke)
        }

        @Test
        fun `of creates tool with removeOnInvoke false`() {
            val matryoshka = MatryoshkaTool.of(
                name = "persistent",
                description = "A persistent category",
                innerTools = emptyList(),
                removeOnInvoke = false,
            )

            assertFalse(matryoshka.removeOnInvoke)
        }

        @Test
        fun `call returns message listing enabled tools`() {
            val innerTool1 = MockTool("tool_a", "Tool A") { Tool.Result.text("a") }
            val innerTool2 = MockTool("tool_b", "Tool B") { Tool.Result.text("b") }

            val matryoshka = MatryoshkaTool.of(
                name = "tools",
                description = "Tools",
                innerTools = listOf(innerTool1, innerTool2),
            )

            val result = matryoshka.call("{}")

            assertTrue(result is Tool.Result.Text)
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("2 tools"))
            assertTrue(text.contains("tool_a"))
            assertTrue(text.contains("tool_b"))
        }

        @Test
        fun `selectTools returns all inner tools by default`() {
            val innerTool1 = MockTool("inner1", "Inner 1") { Tool.Result.text("1") }
            val innerTool2 = MockTool("inner2", "Inner 2") { Tool.Result.text("2") }

            val matryoshka = MatryoshkaTool.of(
                name = "category",
                description = "Category",
                innerTools = listOf(innerTool1, innerTool2),
            )

            val selected = matryoshka.selectTools("{}")
            assertEquals(2, selected.size)
        }
    }

    @Nested
    inner class SelectableMatryoshkaToolTest {

        @Test
        fun `selectable creates tool with custom selector`() {
            val readTool = MockTool("read", "Read files") { Tool.Result.text("read") }
            val writeTool = MockTool("write", "Write files") { Tool.Result.text("write") }

            val matryoshka = MatryoshkaTool.selectable(
                name = "file_ops",
                description = "File operations",
                innerTools = listOf(readTool, writeTool),
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter.string("mode", "Operation mode")
                ),
            ) { input ->
                if (input.contains("read")) listOf(readTool) else listOf(writeTool)
            }

            assertEquals("file_ops", matryoshka.definition.name)
            assertEquals(2, matryoshka.innerTools.size)

            // Test selector
            val readSelected = matryoshka.selectTools("""{"mode": "read"}""")
            assertEquals(1, readSelected.size)
            assertEquals("read", readSelected[0].definition.name)

            val writeSelected = matryoshka.selectTools("""{"mode": "write"}""")
            assertEquals(1, writeSelected.size)
            assertEquals("write", writeSelected[0].definition.name)
        }
    }

    @Nested
    inner class CategoryMatryoshkaToolTest {

        @Test
        fun `byCategory creates tool with category-based selection`() {
            val queryTool = MockTool("query", "Query database") { Tool.Result.text("query") }
            val insertTool = MockTool("insert", "Insert records") { Tool.Result.text("insert") }
            val deleteTool = MockTool("delete", "Delete records") { Tool.Result.text("delete") }

            val matryoshka = MatryoshkaTool.byCategory(
                name = "database",
                description = "Database operations",
                toolsByCategory = mapOf(
                    "read" to listOf(queryTool),
                    "write" to listOf(insertTool, deleteTool),
                ),
            )

            assertEquals("database", matryoshka.definition.name)
            assertEquals(3, matryoshka.innerTools.size) // All tools

            // Test category selection
            val readTools = matryoshka.selectTools("""{"category": "read"}""")
            assertEquals(1, readTools.size)
            assertEquals("query", readTools[0].definition.name)

            val writeTools = matryoshka.selectTools("""{"category": "write"}""")
            assertEquals(2, writeTools.size)
        }

        @Test
        fun `byCategory returns all tools for unknown category`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val matryoshka = MatryoshkaTool.byCategory(
                name = "tools",
                description = "Tools",
                toolsByCategory = mapOf(
                    "cat1" to listOf(tool1),
                    "cat2" to listOf(tool2),
                ),
            )

            val selected = matryoshka.selectTools("""{"category": "unknown"}""")
            assertEquals(2, selected.size)
        }

        @Test
        fun `byCategory returns all tools for missing category`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }

            val matryoshka = MatryoshkaTool.byCategory(
                name = "tools",
                description = "Tools",
                toolsByCategory = mapOf("cat1" to listOf(tool1)),
            )

            val selected = matryoshka.selectTools("{}")
            assertEquals(1, selected.size)
        }

        @Test
        fun `byCategory includes enum values in schema`() {
            val matryoshka = MatryoshkaTool.byCategory(
                name = "tools",
                description = "Tools",
                toolsByCategory = mapOf(
                    "alpha" to emptyList(),
                    "beta" to emptyList(),
                    "gamma" to emptyList(),
                ),
            )

            val schema = matryoshka.definition.inputSchema.toJsonSchema()
            assertTrue(schema.contains("alpha"))
            assertTrue(schema.contains("beta"))
            assertTrue(schema.contains("gamma"))
        }
    }

    @Nested
    inner class MatryoshkaToolInjectionStrategyTest {

        @Test
        fun `strategy ignores non-MatryoshkaTool invocations`() {
            val regularTool = MockTool("regular", "Regular tool") { Tool.Result.text("done") }

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(regularTool),
                lastToolCall = ToolCallResult(
                    toolName = "regular",
                    toolInput = "{}",
                    result = "done",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = MatryoshkaToolInjectionStrategy()
            val result = strategy.evaluate(context)

            assertFalse(result.hasChanges())
        }

        @Test
        fun `strategy replaces MatryoshkaTool with inner tools`() {
            val innerTool = MockTool("inner", "Inner tool") { Tool.Result.text("inner") }
            val matryoshka = MatryoshkaTool.of(
                name = "outer",
                description = "Outer tool",
                innerTools = listOf(innerTool),
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "outer",
                    toolInput = "{}",
                    result = "Enabled 1 tools: inner",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = MatryoshkaToolInjectionStrategy()
            val result = strategy.evaluate(context)

            assertTrue(result.hasChanges())
            assertEquals(1, result.toolsToAdd.size)
            assertEquals("inner", result.toolsToAdd[0].definition.name)
            assertEquals(1, result.toolsToRemove.size)
            assertEquals("outer", result.toolsToRemove[0].definition.name)
        }

        @Test
        fun `strategy keeps MatryoshkaTool when removeOnInvoke is false`() {
            val innerTool = MockTool("inner", "Inner tool") { Tool.Result.text("inner") }
            val matryoshka = MatryoshkaTool.of(
                name = "persistent",
                description = "Persistent tool",
                innerTools = listOf(innerTool),
                removeOnInvoke = false,
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "persistent",
                    toolInput = "{}",
                    result = "Enabled 1 tools: inner",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = MatryoshkaToolInjectionStrategy()
            val result = strategy.evaluate(context)

            assertTrue(result.hasChanges())
            assertEquals(1, result.toolsToAdd.size)
            assertTrue(result.toolsToRemove.isEmpty())
        }

        @Test
        fun `strategy uses selector for selectable MatryoshkaTool`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val matryoshka = MatryoshkaTool.selectable(
                name = "selector",
                description = "Selects tools",
                innerTools = listOf(tool1, tool2),
                inputSchema = Tool.InputSchema.empty(),
            ) { input ->
                if (input.contains("one")) listOf(tool1) else listOf(tool2)
            }

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "selector",
                    toolInput = """{"pick": "one"}""",
                    result = "Enabled 1 tools: tool1",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = MatryoshkaToolInjectionStrategy()
            val result = strategy.evaluate(context)

            assertEquals(1, result.toolsToAdd.size)
            assertEquals("tool1", result.toolsToAdd[0].definition.name)
        }

        @Test
        fun `strategy handles empty selection gracefully`() {
            val matryoshka = MatryoshkaTool.selectable(
                name = "empty",
                description = "Returns empty",
                innerTools = listOf(MockTool("x", "X") { Tool.Result.text("x") }),
                inputSchema = Tool.InputSchema.empty(),
            ) { emptyList() }

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "empty",
                    toolInput = "{}",
                    result = "Enabled 0 tools:",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = MatryoshkaToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // Should still remove the tool, just with no additions
            assertTrue(result.toolsToRemove.isNotEmpty())
            assertTrue(result.toolsToAdd.isEmpty())
        }
    }

    @Nested
    inner class CompositeToolInjectionStrategyTest {

        @Test
        fun `composite combines multiple strategies`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val strategy1 = object : ToolInjectionStrategy {
                override fun evaluate(context: ToolInjectionContext) =
                    ToolInjectionResult.add(tool1)
            }

            val strategy2 = object : ToolInjectionStrategy {
                override fun evaluate(context: ToolInjectionContext) =
                    ToolInjectionResult.add(tool2)
            }

            val composite = CompositeToolInjectionStrategy(strategy1, strategy2)

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = emptyList(),
                lastToolCall = ToolCallResult("x", "{}", "result", null),
                iterationCount = 1,
            )

            val result = composite.evaluate(context)

            assertEquals(2, result.toolsToAdd.size)
        }

        @Test
        fun `withMatryoshka includes MatryoshkaToolInjectionStrategy`() {
            val innerTool = MockTool("inner", "Inner") { Tool.Result.text("inner") }
            val matryoshka = MatryoshkaTool.of(
                name = "outer",
                description = "Outer",
                innerTools = listOf(innerTool),
            )

            val composite = CompositeToolInjectionStrategy.withMatryoshka()

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "outer",
                    toolInput = "{}",
                    result = "Enabled 1 tools: inner",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val result = composite.evaluate(context)

            assertEquals(1, result.toolsToAdd.size)
            assertEquals(1, result.toolsToRemove.size)
        }
    }

    @Nested
    inner class ToolInjectionResultTest {

        @Test
        fun `noChange returns empty result`() {
            val result = ToolInjectionResult.noChange()
            assertFalse(result.hasChanges())
            assertTrue(result.toolsToAdd.isEmpty())
            assertTrue(result.toolsToRemove.isEmpty())
        }

        @Test
        fun `add single tool`() {
            val tool = MockTool("tool", "Tool") { Tool.Result.text("ok") }
            val result = ToolInjectionResult.add(tool)

            assertTrue(result.hasChanges())
            assertEquals(1, result.toolsToAdd.size)
            assertTrue(result.toolsToRemove.isEmpty())
        }

        @Test
        fun `add empty list returns noChange`() {
            val result = ToolInjectionResult.add(emptyList())
            assertFalse(result.hasChanges())
        }

        @Test
        fun `replace tool with others`() {
            val old = MockTool("old", "Old") { Tool.Result.text("old") }
            val new1 = MockTool("new1", "New 1") { Tool.Result.text("new1") }
            val new2 = MockTool("new2", "New 2") { Tool.Result.text("new2") }

            val result = ToolInjectionResult.replace(old, listOf(new1, new2))

            assertTrue(result.hasChanges())
            assertEquals(1, result.toolsToRemove.size)
            assertEquals(2, result.toolsToAdd.size)
        }

        @Test
        fun `remove tools`() {
            val tool = MockTool("tool", "Tool") { Tool.Result.text("ok") }
            val result = ToolInjectionResult.remove(listOf(tool))

            assertTrue(result.hasChanges())
            assertTrue(result.toolsToAdd.isEmpty())
            assertEquals(1, result.toolsToRemove.size)
        }

        @Test
        fun `remove empty list returns noChange`() {
            val result = ToolInjectionResult.remove(emptyList())
            assertFalse(result.hasChanges())
        }
    }

    @Nested
    inner class ToolLoopIntegrationTest {

        @Test
        fun `tool loop removes MatryoshkaTool and adds inner tools`() {
            val innerTool = MockTool("query", "Query database") {
                Tool.Result.text("""{"rows": 5}""")
            }

            val matryoshka = MatryoshkaTool.of(
                name = "database",
                description = "Database operations. Invoke to see specific tools.",
                innerTools = listOf(innerTool),
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // First: LLM invokes the MatryoshkaTool
                    MockLlmMessageSender.toolCallResponse("call_1", "database", "{}"),
                    // Second: LLM uses the now-available inner tool
                    MockLlmMessageSender.toolCallResponse("call_2", "query", """{"sql": "SELECT *"}"""),
                    // Third: LLM provides final answer
                    MockLlmMessageSender.textResponse("Found 5 rows in the database.")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = MatryoshkaToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Query the database")),
                initialTools = listOf(matryoshka),
                outputParser = { it }
            )

            assertEquals("Found 5 rows in the database.", result.result)
            assertEquals(1, result.injectedTools.size)
            assertEquals("query", result.injectedTools[0].definition.name)
            assertEquals(1, result.removedTools.size)
            assertEquals("database", result.removedTools[0].definition.name)
        }

        @Test
        fun `tool loop handles nested MatryoshkaTools`() {
            val leafTool = MockTool("leaf", "Leaf tool") {
                Tool.Result.text("leaf result")
            }

            val innerMatryoshka = MatryoshkaTool.of(
                name = "inner_category",
                description = "Inner category",
                innerTools = listOf(leafTool),
            )

            val outerMatryoshka = MatryoshkaTool.of(
                name = "outer_category",
                description = "Outer category",
                innerTools = listOf(innerMatryoshka),
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // Invoke outer
                    MockLlmMessageSender.toolCallResponse("call_1", "outer_category", "{}"),
                    // Invoke inner (now available)
                    MockLlmMessageSender.toolCallResponse("call_2", "inner_category", "{}"),
                    // Use leaf tool
                    MockLlmMessageSender.toolCallResponse("call_3", "leaf", "{}"),
                    // Final answer
                    MockLlmMessageSender.textResponse("Got leaf result")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmCaller = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = MatryoshkaToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Drill down")),
                initialTools = listOf(outerMatryoshka),
                outputParser = { it }
            )

            assertEquals("Got leaf result", result.result)
            // Both inner matryoshka and leaf were injected
            assertEquals(2, result.injectedTools.size)
            // Both outer and inner matryoshkas were removed
            assertEquals(2, result.removedTools.size)
        }
    }
}
