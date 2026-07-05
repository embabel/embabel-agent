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
package com.embabel.agent.api.tool.progressive

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.UnfoldingTools
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.loop.ChainedToolInjectionStrategy
import com.embabel.agent.spi.loop.MockLlmMessageSender
import com.embabel.agent.spi.loop.MockTool
import com.embabel.agent.spi.loop.ToolCallResult
import com.embabel.agent.spi.loop.ToolInjectionContext
import com.embabel.agent.spi.loop.ToolInjectionResult
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.UnfoldingToolInjectionStrategy
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.chat.UserMessage
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.collections.get

class UnfoldingToolTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class UnfoldingToolCreationTest {

        @Test
        fun `of creates tool with inner tools`() {
            val innerTool1 = MockTool("inner1", "Inner tool 1") { Tool.Result.text("1") }
            val innerTool2 = MockTool("inner2", "Inner tool 2") { Tool.Result.text("2") }

            val matryoshka = UnfoldingTool.of(
                name = "category",
                description = "A category of tools",
                innerTools = listOf(innerTool1, innerTool2),
            )

            Assertions.assertEquals("category", matryoshka.definition.name)
            Assertions.assertEquals("A category of tools", matryoshka.definition.description)
            Assertions.assertEquals(2, matryoshka.innerTools.size)
            Assertions.assertTrue(matryoshka.removeOnInvoke)
        }

        @Test
        fun `of creates tool with removeOnInvoke false`() {
            val matryoshka = UnfoldingTool.of(
                name = "persistent",
                description = "A persistent category",
                innerTools = emptyList(),
                removeOnInvoke = false,
            )

            Assertions.assertFalse(matryoshka.removeOnInvoke)
        }

        @Test
        fun `call returns message listing enabled tools`() {
            val innerTool1 = MockTool("tool_a", "Tool A") { Tool.Result.text("a") }
            val innerTool2 = MockTool("tool_b", "Tool B") { Tool.Result.text("b") }

            val matryoshka = UnfoldingTool.of(
                name = "tools",
                description = "Tools",
                innerTools = listOf(innerTool1, innerTool2),
            )

            val result = matryoshka.call("{}")

            Assertions.assertTrue(result is Tool.Result.Text)
            val text = (result as Tool.Result.Text).content
            Assertions.assertTrue(text.contains("Tools now available"))
            Assertions.assertTrue(text.contains("tool_a"))
            Assertions.assertTrue(text.contains("tool_b"))
        }

        @Test
        fun `selectTools returns all inner tools by default`() {
            val innerTool1 = MockTool("inner1", "Inner 1") { Tool.Result.text("1") }
            val innerTool2 = MockTool("inner2", "Inner 2") { Tool.Result.text("2") }

            val matryoshka = UnfoldingTool.of(
                name = "category",
                description = "Category",
                innerTools = listOf(innerTool1, innerTool2),
            )

            val selected = matryoshka.selectTools("{}")
            Assertions.assertEquals(2, selected.size)
        }

        @Test
        fun `of creates tool with childToolUsageNotes`() {
            val innerTool = MockTool("search", "Search") { Tool.Result.text("results") }

            val matryoshka = UnfoldingTool.of(
                name = "data_tools",
                description = "Tools for data access",
                innerTools = listOf(innerTool),
                childToolUsageNotes = "Try semantic search first before falling back to keyword search.",
            )

            Assertions.assertEquals("data_tools", matryoshka.definition.name)
            Assertions.assertEquals(
                "Try semantic search first before falling back to keyword search.",
                matryoshka.childToolUsageNotes
            )
        }

        @Test
        fun `childToolUsageNotes defaults to null when not specified`() {
            val innerTool = MockTool("tool", "Tool") { Tool.Result.text("result") }

            val matryoshka = UnfoldingTool.of(
                name = "simple",
                description = "Simple tool",
                innerTools = listOf(innerTool),
            )

            Assertions.assertNull(matryoshka.childToolUsageNotes)
        }
    }

    @Nested
    inner class SelectableUnfoldingToolTest {

        @Test
        fun `selectable creates tool with custom selector`() {
            val readTool = MockTool("read", "Read files") { Tool.Result.text("read") }
            val writeTool = MockTool("write", "Write files") { Tool.Result.text("write") }

            val matryoshka = UnfoldingTool.selectable(
                name = "file_ops",
                description = "File operations",
                innerTools = listOf(readTool, writeTool),
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter.string("mode", "Operation mode")
                ),
            ) { input ->
                if (input.contains("read")) listOf(readTool) else listOf(writeTool)
            }

            Assertions.assertEquals("file_ops", matryoshka.definition.name)
            Assertions.assertEquals(2, matryoshka.innerTools.size)

            // Test selector
            val readSelected = matryoshka.selectTools("""{"mode": "read"}""")
            Assertions.assertEquals(1, readSelected.size)
            Assertions.assertEquals("read", readSelected[0].definition.name)

            val writeSelected = matryoshka.selectTools("""{"mode": "write"}""")
            Assertions.assertEquals(1, writeSelected.size)
            Assertions.assertEquals("write", writeSelected[0].definition.name)
        }
    }

    @Nested
    inner class CategoryUnfoldingToolTest {

        @Test
        fun `byCategory creates tool with category-based selection`() {
            val queryTool = MockTool("query", "Query database") { Tool.Result.text("query") }
            val insertTool = MockTool("insert", "Insert records") { Tool.Result.text("insert") }
            val deleteTool = MockTool("delete", "Delete records") { Tool.Result.text("delete") }

            val matryoshka = UnfoldingTool.byCategory(
                name = "database",
                description = "Database operations",
                toolsByCategory = mapOf(
                    "read" to listOf(queryTool),
                    "write" to listOf(insertTool, deleteTool),
                ),
            )

            Assertions.assertEquals("database", matryoshka.definition.name)
            Assertions.assertEquals(3, matryoshka.innerTools.size) // All tools

            // Test category selection
            val readTools = matryoshka.selectTools("""{"category": "read"}""")
            Assertions.assertEquals(1, readTools.size)
            Assertions.assertEquals("query", readTools[0].definition.name)

            val writeTools = matryoshka.selectTools("""{"category": "write"}""")
            Assertions.assertEquals(2, writeTools.size)
        }

        @Test
        fun `byCategory returns all tools for unknown category`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val matryoshka = UnfoldingTool.byCategory(
                name = "tools",
                description = "Tools",
                toolsByCategory = mapOf(
                    "cat1" to listOf(tool1),
                    "cat2" to listOf(tool2),
                ),
            )

            val selected = matryoshka.selectTools("""{"category": "unknown"}""")
            Assertions.assertEquals(2, selected.size)
        }

        @Test
        fun `byCategory returns all tools for missing category`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }

            val matryoshka = UnfoldingTool.byCategory(
                name = "tools",
                description = "Tools",
                toolsByCategory = mapOf("cat1" to listOf(tool1)),
            )

            val selected = matryoshka.selectTools("{}")
            Assertions.assertEquals(1, selected.size)
        }

        @Test
        fun `byCategory includes enum values in schema`() {
            val matryoshka = UnfoldingTool.byCategory(
                name = "tools",
                description = "Tools",
                toolsByCategory = mapOf(
                    "alpha" to emptyList(),
                    "beta" to emptyList(),
                    "gamma" to emptyList(),
                ),
            )

            val schema = matryoshka.definition.inputSchema.toJsonSchema()
            Assertions.assertTrue(schema.contains("alpha"))
            Assertions.assertTrue(schema.contains("beta"))
            Assertions.assertTrue(schema.contains("gamma"))
        }
    }

    @Nested
    inner class UnfoldingToolInjectionStrategyTest {

        @Test
        fun `strategy ignores non-UnfoldingTool invocations`() {
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            Assertions.assertFalse(result.hasChanges())
        }

        @Test
        fun `strategy replaces UnfoldingTool with inner tools`() {
            val innerTool = MockTool("inner", "Inner tool") { Tool.Result.text("inner") }
            val matryoshka = UnfoldingTool.of(
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            Assertions.assertTrue(result.hasChanges())
            // Only inner tools are injected (no guide tool)
            Assertions.assertEquals(1, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            Assertions.assertEquals(1, result.toolsToRemove.size)
            Assertions.assertEquals("outer", result.toolsToRemove[0].definition.name)
        }

        @Suppress("DEPRECATION")
        @Test
        fun `strategy always replaces even when removeOnInvoke is false`() {
            val innerTool = MockTool("inner", "Inner tool") { Tool.Result.text("inner") }
            val matryoshka = UnfoldingTool.of(
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            Assertions.assertTrue(result.hasChanges())
            // removeOnInvoke is deprecated and ignored — only inner tools injected
            Assertions.assertEquals(1, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            Assertions.assertEquals(1, result.toolsToRemove.size)
        }

        @Test
        fun `strategy uses selector for selectable UnfoldingTool`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val matryoshka = UnfoldingTool.selectable(
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // Only selected tool (no guide tool)
            Assertions.assertEquals(1, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "tool1" })
        }

        @Test
        fun `strategy handles empty selection gracefully`() {
            val matryoshka = UnfoldingTool.selectable(
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // Empty selection — no changes (nothing to unfold into)
            Assertions.assertFalse(result.hasChanges())
        }

        @Test
        fun `strategy injects inner tools`() {
            val innerTool1 = MockTool("count", "Count records") { Tool.Result.text("5") }
            val innerTool2 = MockTool("getValues", "Get distinct values") { Tool.Result.text("[]") }
            val matryoshka = UnfoldingTool.of(
                name = "composer_stats",
                description = "Use this to find stats about composers",
                innerTools = listOf(innerTool1, innerTool2),
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "composer_stats",
                    toolInput = "{}",
                    result = "Enabled 2 tools: count, getValues",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // Should inject only inner tools (no guide tool)
            Assertions.assertEquals(2, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "count" })
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "getValues" })
        }

        @Test
        fun `no tools injected when no inner tools selected`() {
            val matryoshka = UnfoldingTool.selectable(
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // No tools when no inner tools
            Assertions.assertTrue(result.toolsToAdd.isEmpty())
        }

        @Test
        fun `exclusive UnfoldingTool removes all other tools`() {
            val innerTool = MockTool("inner", "Inner tool") { Tool.Result.text("inner") }
            val siblingTool = MockTool("sibling", "Sibling tool") { Tool.Result.text("sibling") }
            val exclusiveTool = UnfoldingTool.of(
                name = "exclusive_tool",
                description = "Exclusive tool",
                innerTools = listOf(innerTool),
                exclusive = true,
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(exclusiveTool, siblingTool),
                lastToolCall = ToolCallResult(
                    toolName = "exclusive_tool",
                    toolInput = "{}",
                    result = "Tools now available: inner",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            Assertions.assertTrue(result.hasChanges())
            Assertions.assertEquals(1, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            // All current tools should be removed (both the exclusive tool and the sibling)
            Assertions.assertEquals(2, result.toolsToRemove.size)
            Assertions.assertTrue(result.toolsToRemove.any { it.definition.name == "exclusive_tool" })
            Assertions.assertTrue(result.toolsToRemove.any { it.definition.name == "sibling" })
        }

        @Test
        fun `non-exclusive UnfoldingTool preserves sibling tools`() {
            val innerTool = MockTool("inner", "Inner tool") { Tool.Result.text("inner") }
            val siblingTool = MockTool("sibling", "Sibling tool") { Tool.Result.text("sibling") }
            val nonExclusiveTool = UnfoldingTool.of(
                name = "non_exclusive_tool",
                description = "Non-exclusive tool",
                innerTools = listOf(innerTool),
                exclusive = false,
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(nonExclusiveTool, siblingTool),
                lastToolCall = ToolCallResult(
                    toolName = "non_exclusive_tool",
                    toolInput = "{}",
                    result = "Tools now available: inner",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            Assertions.assertTrue(result.hasChanges())
            Assertions.assertEquals(1, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            // Only the parent tool should be removed, not the sibling
            Assertions.assertEquals(1, result.toolsToRemove.size)
            Assertions.assertEquals("non_exclusive_tool", result.toolsToRemove[0].definition.name)
        }

    }

    @Nested
    inner class ChainedToolInjectionStrategyTest {

        @Test
        fun `chained combines multiple strategies`() {
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

            val chained = ChainedToolInjectionStrategy(strategy1, strategy2)

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = emptyList(),
                lastToolCall = ToolCallResult("x", "{}", "result", null),
                iterationCount = 1,
            )

            val result = chained.evaluate(context)

            Assertions.assertEquals(2, result.toolsToAdd.size)
        }

        @Test
        fun `withMatryoshka includes UnfoldingToolInjectionStrategy`() {
            val innerTool = MockTool("inner", "Inner") { Tool.Result.text("inner") }
            val matryoshka = UnfoldingTool.of(
                name = "outer",
                description = "Outer",
                innerTools = listOf(innerTool),
            )

            val chained = ChainedToolInjectionStrategy.withMatryoshka()

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

            val result = chained.evaluate(context)

            // Only inner tool (no guide tool)
            Assertions.assertEquals(1, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            Assertions.assertEquals(1, result.toolsToRemove.size)
        }
    }

    @Nested
    inner class ToolInjectionResultTest {

        @Test
        fun `noChange returns empty result`() {
            val result = ToolInjectionResult.noChange()
            Assertions.assertFalse(result.hasChanges())
            Assertions.assertTrue(result.toolsToAdd.isEmpty())
            Assertions.assertTrue(result.toolsToRemove.isEmpty())
        }

        @Test
        fun `add single tool`() {
            val tool = MockTool("tool", "Tool") { Tool.Result.text("ok") }
            val result = ToolInjectionResult.add(tool)

            Assertions.assertTrue(result.hasChanges())
            Assertions.assertEquals(1, result.toolsToAdd.size)
            Assertions.assertTrue(result.toolsToRemove.isEmpty())
        }

        @Test
        fun `add empty list returns noChange`() {
            val result = ToolInjectionResult.add(emptyList())
            Assertions.assertFalse(result.hasChanges())
        }

        @Test
        fun `replace tool with others`() {
            val old = MockTool("old", "Old") { Tool.Result.text("old") }
            val new1 = MockTool("new1", "New 1") { Tool.Result.text("new1") }
            val new2 = MockTool("new2", "New 2") { Tool.Result.text("new2") }

            val result = ToolInjectionResult.replace(old, listOf(new1, new2))

            Assertions.assertTrue(result.hasChanges())
            Assertions.assertEquals(1, result.toolsToRemove.size)
            Assertions.assertEquals(2, result.toolsToAdd.size)
        }

        @Test
        fun `remove tools`() {
            val tool = MockTool("tool", "Tool") { Tool.Result.text("ok") }
            val result = ToolInjectionResult.remove(listOf(tool))

            Assertions.assertTrue(result.hasChanges())
            Assertions.assertTrue(result.toolsToAdd.isEmpty())
            Assertions.assertEquals(1, result.toolsToRemove.size)
        }

        @Test
        fun `remove empty list returns noChange`() {
            val result = ToolInjectionResult.remove(emptyList())
            Assertions.assertFalse(result.hasChanges())
        }
    }

    @Nested
    inner class ToolLoopIntegrationTest {

        @Test
        fun `tool loop removes UnfoldingTool and adds inner tools`() {
            val innerTool = MockTool("query", "Query database") {
                Tool.Result.text("""{"rows": 5}""")
            }

            val matryoshka = UnfoldingTool.of(
                name = "database",
                description = "Database operations. Invoke to see specific tools.",
                innerTools = listOf(innerTool),
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // First: LLM invokes the UnfoldingTool
                    MockLlmMessageSender.toolCallResponse("call_1", "database", "{}"),
                    // Second: LLM uses the now-available inner tool
                    MockLlmMessageSender.toolCallResponse("call_2", "query", """{"sql": "SELECT *"}"""),
                    // Third: LLM provides final answer
                    MockLlmMessageSender.textResponse("Found 5 rows in the database.")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Query the database")),
                initialTools = listOf(matryoshka),
                outputParser = { it }
            )

            Assertions.assertEquals("Found 5 rows in the database.", result.result)
            // Only inner tool (no guide tool)
            Assertions.assertEquals(1, result.injectedTools.size)
            Assertions.assertTrue(result.injectedTools.any { it.definition.name == "query" })
            Assertions.assertEquals(1, result.removedTools.size)
            Assertions.assertEquals("database", result.removedTools[0].definition.name)
        }

        @Test
        fun `tool loop handles nested UnfoldingTool`() {
            val leafTool = MockTool("leaf", "Leaf tool") {
                Tool.Result.text("leaf result")
            }

            val innerMatryoshka = UnfoldingTool.of(
                name = "inner_category",
                description = "Inner category",
                innerTools = listOf(leafTool),
            )

            val outerMatryoshka = UnfoldingTool.of(
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
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Drill down")),
                initialTools = listOf(outerMatryoshka),
                outputParser = { it }
            )

            Assertions.assertEquals("Got leaf result", result.result)
            // Both matryoshkas inject only their inner tools (no guide tools):
            // outer injects: inner_category
            // inner injects: leaf
            Assertions.assertEquals(2, result.injectedTools.size)
            Assertions.assertTrue(result.injectedTools.any { it.definition.name == "inner_category" })
            Assertions.assertTrue(result.injectedTools.any { it.definition.name == "leaf" })
            // Both outer and inner matryoshkas were removed
            Assertions.assertEquals(2, result.removedTools.size)
        }
    }

    @Nested
    inner class AnnotationBasedUnfoldingToolTest {

        @Test
        fun `fromInstance creates simple UnfoldingTool from annotated class`() {
            val matryoshka = UnfoldingTool.fromInstance(SimpleDatabaseTools())

            Assertions.assertEquals("database_operations", matryoshka.definition.name)
            Assertions.assertEquals(
                "Database operations. Invoke to see specific tools.",
                matryoshka.definition.description
            )
            Assertions.assertEquals(2, matryoshka.innerTools.size)
            Assertions.assertTrue(matryoshka.removeOnInvoke)

            val toolNames = matryoshka.innerTools.map { it.definition.name }
            Assertions.assertTrue(toolNames.contains("query"))
            Assertions.assertTrue(toolNames.contains("insert"))
        }

        @Test
        fun `fromInstance creates category-based UnfoldingTool when categories are used`() {
            val matryoshka = UnfoldingTool.fromInstance(CategoryBasedFileTools())

            Assertions.assertEquals("file_operations", matryoshka.definition.name)
            Assertions.assertEquals(4, matryoshka.innerTools.size) // 2 read + 2 write

            // Verify category selection works
            val readTools = matryoshka.selectTools("""{"category": "read"}""")
            Assertions.assertEquals(2, readTools.size)
            Assertions.assertTrue(readTools.all { it.definition.name in listOf("readFile", "listDir") })

            val writeTools = matryoshka.selectTools("""{"category": "write"}""")
            Assertions.assertEquals(2, writeTools.size)
            Assertions.assertTrue(writeTools.all { it.definition.name in listOf("writeFile", "deleteFile") })
        }

        @Test
        fun `fromInstance respects removeOnInvoke annotation attribute`() {
            val matryoshka = UnfoldingTool.fromInstance(PersistentTools())

            Assertions.assertEquals("persistent_tools", matryoshka.definition.name)
            Assertions.assertFalse(matryoshka.removeOnInvoke)
        }

        @Test
        fun `fromInstance respects childToolUsageNotes annotation attribute`() {
            val matryoshka = UnfoldingTool.fromInstance(MusicSearchTools())

            Assertions.assertEquals("music_search", matryoshka.definition.name)
            Assertions.assertEquals(
                "Search music database for artists, albums, and tracks",
                matryoshka.definition.description
            )
            Assertions.assertEquals(
                "Try vector search first for semantic queries. Use text search for exact artist names.",
                matryoshka.childToolUsageNotes
            )
        }

        @Test
        fun `fromInstance throws for class without UnfoldingTool annotation`() {
            val exception = assertThrows<IllegalArgumentException> {
                UnfoldingTool.fromInstance(NonAnnotatedClass())
            }
            Assertions.assertTrue(exception.message!!.contains("not annotated with @UnfoldingTool"))
        }

        @Test
        fun `fromInstance throws for class without LlmTool methods`() {
            val exception = assertThrows<IllegalArgumentException> {
                UnfoldingTool.fromInstance(NoToolMethods())
            }
            Assertions.assertTrue(exception.message!!.contains("no methods annotated with @LlmTool"))
        }

        @Test
        fun `safelyFromInstance returns null for non-annotated class`() {
            val result = UnfoldingTool.safelyFromInstance(NonAnnotatedClass())
            Assertions.assertNull(result)
        }

        @Test
        fun `safelyFromInstance returns UnfoldingTool for valid class`() {
            val result = UnfoldingTool.safelyFromInstance(SimpleDatabaseTools())
            Assertions.assertNotNull(result)
            Assertions.assertEquals("database_operations", result!!.definition.name)
        }

        @Test
        fun `category-based UnfoldingTool includes uncategorized tools in all category`() {
            val matryoshka = UnfoldingTool.fromInstance(MixedCategoryTools())

            // The "all" category should include everything
            val allTools = matryoshka.selectTools("""{"category": "all"}""")
            Assertions.assertEquals(3, allTools.size)

            // Read category should have read tool + uncategorized tool
            val readTools = matryoshka.selectTools("""{"category": "read"}""")
            Assertions.assertEquals(2, readTools.size)
        }

        @Test
        fun `tools from annotated class are callable`() {
            val matryoshka = UnfoldingTool.fromInstance(SimpleDatabaseTools())

            val queryTool = matryoshka.innerTools.find { it.definition.name == "query" }!!
            val result = queryTool.call("""{"sql": "SELECT * FROM users"}""")

            Assertions.assertTrue(result is Tool.Result.Text)
            Assertions.assertTrue((result as Tool.Result.Text).content.contains("5 rows"))
        }

        @Test
        fun `Tool_fromInstance returns UnfoldingTool when class has UnfoldingTool annotation`() {
            val tools = Tool.fromInstance(SimpleDatabaseTools())

            Assertions.assertEquals(1, tools.size)
            Assertions.assertTrue(tools[0] is UnfoldingTool)
            Assertions.assertEquals("database_operations", tools[0].definition.name)

            val matryoshka = tools[0] as UnfoldingTool
            Assertions.assertEquals(2, matryoshka.innerTools.size)
        }

        @Test
        fun `Tool_fromInstance returns individual tools when class lacks UnfoldingTool annotation`() {
            val tools = Tool.fromInstance(NonAnnotatedClass())

            Assertions.assertEquals(1, tools.size)
            Assertions.assertFalse(tools[0] is UnfoldingTool)
            Assertions.assertEquals("tool", tools[0].definition.name)
        }

        @Test
        fun `Tool_safelyFromInstance returns UnfoldingTool when class has UnfoldingTool annotation`() {
            val tools = Tool.safelyFromInstance(SimpleDatabaseTools())

            Assertions.assertEquals(1, tools.size)
            Assertions.assertTrue(tools[0] is UnfoldingTool)
        }
    }

    @Nested
    inner class ConfiguredInnerToolsTest {

        @Test
        fun `UnfoldingTool can pass parameters to configure inner tools`() {
            // Create a UnfoldingTool that creates configured instances based on input
            val matryoshka = UnfoldingTool.selectable(
                name = "database",
                description = "Database operations. Pass 'connection' to configure tools.",
                innerTools = emptyList(), // Inner tools will be created dynamically
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter.string("connection", "Database connection string")
                ),
            ) { input ->
                // Parse the connection parameter from input
                val connectionString = try {
                    val params = objectMapper.readValue(input, Map::class.java)
                    params["connection"] as? String ?: "default"
                } catch (e: Exception) {
                    "default"
                }

                // Create configured tool instances
                listOf(
                    Tool.of(
                        name = "query",
                        description = "Query database at $connectionString"
                    ) { _ ->
                        Tool.Result.text("Connected to $connectionString and executed query")
                    },
                    Tool.of(
                        name = "insert",
                        description = "Insert into database at $connectionString"
                    ) { _ ->
                        Tool.Result.text("Inserted into $connectionString")
                    }
                )
            }

            // Test that different connection strings produce differently configured tools
            val prodTools = matryoshka.selectTools("""{"connection": "prod-db.example.com"}""")
            Assertions.assertEquals(2, prodTools.size)
            Assertions.assertTrue(prodTools[0].definition.description.contains("prod-db.example.com"))

            val devTools = matryoshka.selectTools("""{"connection": "localhost:5432"}""")
            Assertions.assertEquals(2, devTools.size)
            Assertions.assertTrue(devTools[0].definition.description.contains("localhost:5432"))

            // Verify the tools are callable with the configured connection
            val result = prodTools[0].call("{}")
            Assertions.assertTrue((result as Tool.Result.Text).content.contains("prod-db.example.com"))
        }

        @Test
        fun `tool loop uses configured inner tools from UnfoldingTool parameters`() {
            // Create a UnfoldingTool that configures tools based on user selection
            var capturedRegion = ""
            val regionTool = UnfoldingTool.selectable(
                name = "cloud_services",
                description = "Cloud operations. Pass 'region' to select datacenter.",
                innerTools = emptyList(),
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter.string("region", "Cloud region", true, listOf("us-east", "eu-west", "ap-south"))
                ),
            ) { input ->
                val region = try {
                    val params = objectMapper.readValue(input, Map::class.java)
                    params["region"] as? String ?: "us-east"
                } catch (e: Exception) {
                    "us-east"
                }
                capturedRegion = region

                listOf(
                    Tool.of("deploy", "Deploy to $region") { _ ->
                        Tool.Result.text("Deployed to region $region")
                    }
                )
            }

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // LLM invokes with region parameter
                    MockLlmMessageSender.toolCallResponse(
                        "c1", "cloud_services",
                        """{"region": "eu-west"}"""
                    ),
                    // LLM uses the configured deploy tool
                    MockLlmMessageSender.toolCallResponse("c2", "deploy", "{}"),
                    MockLlmMessageSender.textResponse("Deployment complete")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Deploy to EU")),
                initialTools = listOf(regionTool),
                outputParser = { it }
            )

            Assertions.assertEquals("Deployment complete", result.result)
            Assertions.assertEquals("eu-west", capturedRegion)
            // Verify the injected tool was configured with the region
            val deployTool = result.injectedTools.find { it.definition.name == "deploy" }
            Assertions.assertNotNull(deployTool)
            Assertions.assertTrue(deployTool!!.definition.description.contains("eu-west"))
        }

        @Test
        fun `UnfoldingTool can create stateful tool instances`() {
            // Create a UnfoldingTool that creates tools with captured state
            val matryoshka = UnfoldingTool.selectable(
                name = "shopping_cart",
                description = "Shopping cart operations. Pass 'cart_id' to select cart.",
                innerTools = emptyList(),
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter.string("cart_id", "Shopping cart ID")
                ),
            ) { input ->
                val cartId = try {
                    val params = objectMapper.readValue(input, Map::class.java)
                    params["cart_id"] as? String ?: "default-cart"
                } catch (e: Exception) {
                    "default-cart"
                }

                // Simulated cart state
                val cartItems = mutableListOf<String>()

                listOf(
                    Tool.of(
                        name = "add_item",
                        description = "Add item to cart $cartId",
                        inputSchema = Tool.InputSchema.of(
                            Tool.Parameter.string("item", "Item to add")
                        )
                    ) { itemInput ->
                        val itemParams = objectMapper.readValue(itemInput, Map::class.java)
                        val item = itemParams["item"] as? String ?: "unknown"
                        cartItems.add(item)
                        Tool.Result.text("Added $item to cart $cartId. Items: ${cartItems.size}")
                    },
                    Tool.of(
                        name = "get_cart",
                        description = "Get contents of cart $cartId"
                    ) { _ ->
                        Tool.Result.text("Cart $cartId contains: ${cartItems.joinToString(", ")}")
                    }
                )
            }

            // Get tools for a specific cart
            val cartTools = matryoshka.selectTools("""{"cart_id": "cart-123"}""")
            Assertions.assertEquals(2, cartTools.size)

            // Add items and verify state is maintained
            val addTool = cartTools.find { it.definition.name == "add_item" }!!
            val getTool = cartTools.find { it.definition.name == "get_cart" }!!

            addTool.call("""{"item": "apple"}""")
            addTool.call("""{"item": "banana"}""")

            val result = getTool.call("{}")
            val content = (result as Tool.Result.Text).content
            Assertions.assertTrue(content.contains("apple"))
            Assertions.assertTrue(content.contains("banana"))
            Assertions.assertTrue(content.contains("cart-123"))
        }
    }

    @Nested
    inner class DeepNestingProgrammaticTest {

        @Test
        fun `three level nesting with programmatic interface`() {
            // Level 3 - leaf tools
            val leafTool1 = MockTool("leaf_query", "Execute query") { Tool.Result.text("query result") }
            val leafTool2 = MockTool("leaf_insert", "Insert data") { Tool.Result.text("insert result") }

            // Level 2 - contains leaf tools
            val level2 = UnfoldingTool.of(
                name = "level2_database",
                description = "Database operations",
                innerTools = listOf(leafTool1, leafTool2),
            )

            // Level 1 - contains level 2
            val level1 = UnfoldingTool.of(
                name = "level1_admin",
                description = "Admin operations",
                innerTools = listOf(level2),
            )

            // Verify structure
            Assertions.assertEquals("level1_admin", level1.definition.name)
            Assertions.assertEquals(1, level1.innerTools.size)

            val innerLevel2 = level1.innerTools[0] as UnfoldingTool
            Assertions.assertEquals("level2_database", innerLevel2.definition.name)
            Assertions.assertEquals(2, innerLevel2.innerTools.size)
        }

        @Test
        fun `five level nesting with programmatic interface`() {
            // Level 5 - deepest leaf tools
            val leaf1 = MockTool("deep_read", "Read data") { Tool.Result.text("read") }
            val leaf2 = MockTool("deep_write", "Write data") { Tool.Result.text("write") }

            // Level 4
            val level4 = UnfoldingTool.of(
                name = "level4_io",
                description = "I/O operations",
                innerTools = listOf(leaf1, leaf2),
            )

            // Level 3
            val level3 = UnfoldingTool.of(
                name = "level3_storage",
                description = "Storage operations",
                innerTools = listOf(level4),
            )

            // Level 2
            val level2 = UnfoldingTool.of(
                name = "level2_data",
                description = "Data operations",
                innerTools = listOf(level3),
            )

            // Level 1 - top
            val level1 = UnfoldingTool.of(
                name = "level1_root",
                description = "Root operations",
                innerTools = listOf(level2),
            )

            // Verify the chain
            Assertions.assertEquals("level1_root", level1.definition.name)
            val l2 = level1.innerTools[0] as UnfoldingTool
            Assertions.assertEquals("level2_data", l2.definition.name)
            val l3 = l2.innerTools[0] as UnfoldingTool
            Assertions.assertEquals("level3_storage", l3.definition.name)
            val l4 = l3.innerTools[0] as UnfoldingTool
            Assertions.assertEquals("level4_io", l4.definition.name)
            Assertions.assertEquals(2, l4.innerTools.size)
            Assertions.assertEquals("deep_read", l4.innerTools[0].definition.name)
            Assertions.assertEquals("deep_write", l4.innerTools[1].definition.name)
        }

        @Test
        fun `tool loop handles five level nesting`() {
            // Build 5-level hierarchy
            val leaf = MockTool("leaf", "Leaf tool") { Tool.Result.text("leaf result") }
            val level4 = UnfoldingTool.of("level4", "Level 4", listOf(leaf))
            val level3 = UnfoldingTool.of("level3", "Level 3", listOf(level4))
            val level2 = UnfoldingTool.of("level2", "Level 2", listOf(level3))
            val level1 = UnfoldingTool.of("level1", "Level 1", listOf(level2))

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("c1", "level1", "{}"),
                    MockLlmMessageSender.toolCallResponse("c2", "level2", "{}"),
                    MockLlmMessageSender.toolCallResponse("c3", "level3", "{}"),
                    MockLlmMessageSender.toolCallResponse("c4", "level4", "{}"),
                    MockLlmMessageSender.toolCallResponse("c5", "leaf", "{}"),
                    MockLlmMessageSender.textResponse("Traversed 5 levels to reach leaf")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Drill deep")),
                initialTools = listOf(level1),
                outputParser = { it }
            )

            Assertions.assertEquals("Traversed 5 levels to reach leaf", result.result)
            // 4 matryoshka tools removed (level1-4)
            Assertions.assertEquals(4, result.removedTools.size)
            // 4 injected: 4 inner tools (level2-4 + leaf), no guide tools
            Assertions.assertEquals(4, result.injectedTools.size)
            Assertions.assertTrue(result.injectedTools.any { it.definition.name == "leaf" })
        }

        @Test
        fun `mixed nesting with multiple branches at each level`() {
            // Level 2 branches
            val branch1Leaf = MockTool("b1_leaf", "Branch 1 leaf") { Tool.Result.text("b1") }
            val branch2Leaf = MockTool("b2_leaf", "Branch 2 leaf") { Tool.Result.text("b2") }

            val branch1 = UnfoldingTool.of(
                name = "branch1",
                description = "Branch 1",
                innerTools = listOf(branch1Leaf),
            )

            val branch2 = UnfoldingTool.of(
                name = "branch2",
                description = "Branch 2",
                innerTools = listOf(branch2Leaf),
            )

            // Level 1 with two branches
            val level1 = UnfoldingTool.of(
                name = "root",
                description = "Root with branches",
                innerTools = listOf(branch1, branch2),
            )

            // Verify structure
            Assertions.assertEquals(2, level1.innerTools.size)
            Assertions.assertTrue(level1.innerTools[0] is UnfoldingTool)
            Assertions.assertTrue(level1.innerTools[1] is UnfoldingTool)

            // Test branch selection via tool loop
            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("c1", "root", "{}"),
                    MockLlmMessageSender.toolCallResponse("c2", "branch1", "{}"),
                    MockLlmMessageSender.toolCallResponse("c3", "b1_leaf", "{}"),
                    MockLlmMessageSender.textResponse("Used branch 1")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Use branch 1")),
                initialTools = listOf(level1),
                outputParser = { it }
            )

            Assertions.assertEquals("Used branch 1", result.result)
            // root and branch1 removed, branch1+branch2+b1_leaf injected
            Assertions.assertEquals(2, result.removedTools.size)
            Assertions.assertTrue(result.removedTools.any { it.definition.name == "root" })
            Assertions.assertTrue(result.removedTools.any { it.definition.name == "branch1" })
        }
    }

    @Nested
    inner class DeepNestingAnnotationTest {

        @Test
        fun `creates UnfoldingTool with nested inner class UnfoldingTool`() {
            val matryoshka = UnfoldingTool.fromInstance(Level2Category())

            Assertions.assertEquals("level2_category", matryoshka.definition.name)
            // Should have 1 direct tool + 1 inner UnfoldingTool
            Assertions.assertEquals(2, matryoshka.innerTools.size)

            val directTool = matryoshka.innerTools.find { it.definition.name == "level2Util" }
            Assertions.assertNotNull(directTool)
            Assertions.assertFalse(directTool is UnfoldingTool)

            val innerMatryoshka = matryoshka.innerTools.find { it.definition.name == "level3_inner" }
            Assertions.assertNotNull(innerMatryoshka)
            Assertions.assertTrue(innerMatryoshka is UnfoldingTool)

            val level3 = innerMatryoshka as UnfoldingTool
            Assertions.assertEquals(2, level3.innerTools.size)
            Assertions.assertTrue(level3.innerTools.any { it.definition.name == "innerQuery" })
            Assertions.assertTrue(level3.innerTools.any { it.definition.name == "innerInsert" })
        }

        @Test
        fun `creates three level deep UnfoldingTool hierarchy from nested annotations`() {
            val level1 = UnfoldingTool.fromInstance(Level1Top())

            Assertions.assertEquals("level1_top", level1.definition.name)
            // 1 direct tool (status) + 1 inner UnfoldingTool (level2_inner)
            Assertions.assertEquals(2, level1.innerTools.size)

            val statusTool = level1.innerTools.find { it.definition.name == "status" }
            Assertions.assertNotNull(statusTool)

            val level2 = level1.innerTools.find { it.definition.name == "level2_inner" } as? UnfoldingTool
            Assertions.assertNotNull(level2)
            // 1 direct tool (level2Op) + 1 inner UnfoldingTool (level3_deepest)
            Assertions.assertEquals(2, level2!!.innerTools.size)

            val level2Op = level2.innerTools.find { it.definition.name == "level2Op" }
            Assertions.assertNotNull(level2Op)

            val level3 = level2.innerTools.find { it.definition.name == "level3_deepest" } as? UnfoldingTool
            Assertions.assertNotNull(level3)
            Assertions.assertEquals(2, level3!!.innerTools.size)
            Assertions.assertTrue(level3.innerTools.any { it.definition.name == "deepQuery" })
            Assertions.assertTrue(level3.innerTools.any { it.definition.name == "deepMutate" })
        }

        @Test
        fun `tool loop traverses annotation-based nested hierarchy`() {
            val level1 = UnfoldingTool.fromInstance(Level1Top())

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    // Invoke level1
                    MockLlmMessageSender.toolCallResponse("c1", "level1_top", "{}"),
                    // Invoke level2
                    MockLlmMessageSender.toolCallResponse("c2", "level2_inner", "{}"),
                    // Invoke level3
                    MockLlmMessageSender.toolCallResponse("c3", "level3_deepest", "{}"),
                    // Use deepest tool
                    MockLlmMessageSender.toolCallResponse("c4", "deepQuery", "{}"),
                    MockLlmMessageSender.textResponse("Executed deep query")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Run deep query")),
                initialTools = listOf(level1),
                outputParser = { it }
            )

            Assertions.assertEquals("Executed deep query", result.result)
            // All 3 matryoshka levels removed
            Assertions.assertEquals(3, result.removedTools.size)
            Assertions.assertTrue(result.removedTools.any { it.definition.name == "level1_top" })
            Assertions.assertTrue(result.removedTools.any { it.definition.name == "level2_inner" })
            Assertions.assertTrue(result.removedTools.any { it.definition.name == "level3_deepest" })
        }

        @Test
        fun `inner tools from nested annotations are callable`() {
            val level1 = UnfoldingTool.fromInstance(Level1Top())

            // Get level2
            val level2 = level1.innerTools.find { it.definition.name == "level2_inner" } as UnfoldingTool

            // Get level3
            val level3 = level2.innerTools.find { it.definition.name == "level3_deepest" } as UnfoldingTool

            // Call the deepest tool
            val deepQueryTool = level3.innerTools.find { it.definition.name == "deepQuery" }!!
            val result = deepQueryTool.call("{}")

            Assertions.assertTrue(result is Tool.Result.Text)
            Assertions.assertEquals("Deep query result", (result as Tool.Result.Text).content)
        }

        @Test
        fun `Tool_fromInstance detects nested UnfoldingTool in inner classes`() {
            val tools = Tool.fromInstance(Level1Top())

            Assertions.assertEquals(1, tools.size)
            Assertions.assertTrue(tools[0] is UnfoldingTool)

            val level1 = tools[0] as UnfoldingTool
            Assertions.assertEquals("level1_top", level1.definition.name)

            // Verify nested structure
            val level2 = level1.innerTools.find { it is UnfoldingTool && it.definition.name == "level2_inner" }
            Assertions.assertNotNull(level2)
        }
    }

    @Nested
    inner class InjectedToolDecorationTest {

        /**
         * Verifies that when DefaultToolLoop has a toolDecorator,
         * injected tools from UnfoldingTool are decorated.
         */
        @Test
        fun `injected tools should be decorated when toolDecorator is provided`() {
            val decoratedToolNames = mutableListOf<String>()

            // Decorator that tracks which tools are decorated
            val trackingDecorator: (Tool) -> Tool = { tool ->
                decoratedToolNames.add(tool.definition.name)
                tool // Just track, don't actually wrap
            }

            val childTool = MockTool("child_tool", "Child tool") {
                Tool.Result.text("child result")
            }

            val matryoshka = UnfoldingTool.of(
                name = "parent",
                description = "Parent tool",
                innerTools = listOf(childTool),
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("c1", "parent", "{}"),
                    MockLlmMessageSender.toolCallResponse("c2", "child_tool", "{}"),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
                toolDecorator = trackingDecorator,
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Test")),
                initialTools = listOf(matryoshka),
                outputParser = { it }
            )

            // Injected child tool should be decorated
            Assertions.assertTrue(
                decoratedToolNames.contains("child_tool"),
                "Child tool should be decorated, but only these were: $decoratedToolNames"
            )
        }

        /**
         * Verifies that injected tools in the result are the decorated versions.
         */
        @Test
        fun `injectedTools in result are the decorated versions`() {
            val childTool = MockTool("child_tool", "Child tool") {
                Tool.Result.text("child result")
            }

            // Wrapper that marks decorated tools
            class DecoratedTool(val delegate: Tool) : Tool by delegate

            val matryoshka = UnfoldingTool.of(
                name = "parent",
                description = "Parent tool",
                innerTools = listOf(childTool),
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("c1", "parent", "{}"),
                    MockLlmMessageSender.toolCallResponse("c2", "child_tool", "{}"),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
                toolDecorator = { tool -> DecoratedTool(tool) },
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Test")),
                initialTools = listOf(matryoshka),
                outputParser = { it }
            )

            // The injected tool should be wrapped in DecoratedTool
            val injectedTool = result.injectedTools.first()
            Assertions.assertTrue(
                injectedTool is DecoratedTool,
                "Injected tool should be decorated"
            )
        }

        /**
         * Verifies that nested UnfoldingTool child tools are all decorated.
         */
        @Test
        fun `nested UnfoldingTool child tools are all decorated`() {
            val decoratedToolNames = mutableListOf<String>()

            val leafTool = MockTool("leaf", "Leaf tool") {
                Tool.Result.text("leaf result")
            }

            val innerMatryoshka = UnfoldingTool.of(
                name = "inner",
                description = "Inner category",
                innerTools = listOf(leafTool),
            )

            val outerMatryoshka = UnfoldingTool.of(
                name = "outer",
                description = "Outer category",
                innerTools = listOf(innerMatryoshka),
            )

            val mockCaller = MockLlmMessageSender(
                responses = listOf(
                    MockLlmMessageSender.toolCallResponse("c1", "outer", "{}"),
                    MockLlmMessageSender.toolCallResponse("c2", "inner", "{}"),
                    MockLlmMessageSender.toolCallResponse("c3", "leaf", "{}"),
                    MockLlmMessageSender.textResponse("Done")
                )
            )

            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
                toolDecorator = { tool ->
                    decoratedToolNames.add(tool.definition.name)
                    tool
                },
            )

            toolLoop.execute(
                initialMessages = listOf(UserMessage("Test")),
                initialTools = listOf(outerMatryoshka),
                outputParser = { it }
            )

            // Both inner matryoshka and leaf should be decorated
            Assertions.assertTrue(decoratedToolNames.contains("inner"), "inner should be decorated")
            Assertions.assertTrue(decoratedToolNames.contains("leaf"), "leaf should be decorated")
        }
    }

    @Nested
    inner class UnfoldingToolBuilderTests {

        @Test
        fun `withTools adds tools to existing UnfoldingTool`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }
            val tool3 = MockTool("tool3", "Tool 3") { Tool.Result.text("3") }

            val initial = UnfoldingTool.of(
                name = "combined",
                description = "Combined tools",
                innerTools = listOf(tool1)
            )

            val combined = initial.withTools(tool2, tool3)

            Assertions.assertEquals("combined", combined.definition.name)
            Assertions.assertEquals(3, combined.innerTools.size)
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "tool1" })
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "tool2" })
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "tool3" })
        }

        @Test
        fun `withTools preserves other properties`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val initial = UnfoldingTool.of(
                name = "mytools",
                description = "My tools description",
                innerTools = listOf(tool1),
                removeOnInvoke = false,
                childToolUsageNotes = "Use tool1 for primary operations"
            )

            val combined = initial.withTools(tool2)

            Assertions.assertEquals("mytools", combined.definition.name)
            Assertions.assertEquals("My tools description", combined.definition.description)
            Assertions.assertEquals(false, combined.removeOnInvoke)
            Assertions.assertEquals("Use tool1 for primary operations", combined.childToolUsageNotes)
        }

        @Test
        fun `withToolObject adds tools from annotated object`() {
            val tool1 = MockTool("existing", "Existing tool") { Tool.Result.text("existing") }

            val initial = UnfoldingTool.of(
                name = "combined",
                description = "Combined tools",
                innerTools = listOf(tool1)
            )

            val combined = initial.withToolObject(BuilderTestTools())

            Assertions.assertEquals(3, combined.innerTools.size)
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "existing" })
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "builderSearch" })
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "builderFilter" })
        }

        @Test
        fun `withToolObject preserves properties`() {
            val initial = UnfoldingTool.of(
                name = "mytools",
                description = "My description",
                innerTools = emptyList(),
                removeOnInvoke = false,
                childToolUsageNotes = "Custom notes"
            )

            val combined = initial.withToolObject(BuilderTestTools())

            Assertions.assertEquals("mytools", combined.definition.name)
            Assertions.assertEquals("My description", combined.definition.description)
            Assertions.assertEquals(false, combined.removeOnInvoke)
            Assertions.assertEquals("Custom notes", combined.childToolUsageNotes)
        }

        @Test
        fun `chaining withTools and withToolObject`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val initial = UnfoldingTool.of(
                name = "chained",
                description = "Chained tools",
                innerTools = listOf(tool1)
            )

            val combined = initial
                .withTools(tool2)
                .withToolObject(BuilderTestTools())

            Assertions.assertEquals(4, combined.innerTools.size)
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "tool1" })
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "tool2" })
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "builderSearch" })
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "builderFilter" })
        }

        @Test
        fun `tools added via withToolObject are callable`() {
            val initial = UnfoldingTool.of(
                name = "test",
                description = "Test",
                innerTools = emptyList()
            )

            val combined = initial.withToolObject(BuilderTestTools())

            val searchTool = combined.innerTools.find { it.definition.name == "builderSearch" }!!
            val result = searchTool.call("""{"query": "test query"}""")

            Assertions.assertTrue(result is Tool.Result.Text)
            Assertions.assertTrue((result as Tool.Result.Text).content.contains("test query"))
        }
    }

    @Nested
    inner class FromToolObjectTest {

        @Test
        fun `creates UnfoldingTool from object with LlmTool methods`() {
            val result = UnfoldingTool.fromToolObject(
                instance = PlainToolMethods(),
                name = "plain_tools",
                description = "Plain tools description",
            )

            Assertions.assertEquals("plain_tools", result.definition.name)
            Assertions.assertEquals("Plain tools description", result.definition.description)
            Assertions.assertEquals(2, result.innerTools.size)
            val toolNames = result.innerTools.map { it.definition.name }
            Assertions.assertTrue(toolNames.contains("doSearch"))
            Assertions.assertTrue(toolNames.contains("doFilter"))
        }

        @Test
        fun `uses provided name and description`() {
            val result = UnfoldingTool.fromToolObject(
                instance = PlainToolMethods(),
                name = "custom_name",
                description = "Custom description",
            )

            Assertions.assertEquals("custom_name", result.definition.name)
            Assertions.assertEquals("Custom description", result.definition.description)
        }

        @Test
        fun `passes through removeOnInvoke`() {
            val result = UnfoldingTool.fromToolObject(
                instance = PlainToolMethods(),
                name = "tools",
                description = "Tools",
                removeOnInvoke = false,
            )

            Assertions.assertFalse(result.removeOnInvoke)
        }

        @Test
        fun `passes through childToolUsageNotes`() {
            val result = UnfoldingTool.fromToolObject(
                instance = PlainToolMethods(),
                name = "tools",
                description = "Tools",
                childToolUsageNotes = "Use doSearch for queries, doFilter for filtering.",
            )

            Assertions.assertEquals("Use doSearch for queries, doFilter for filtering.", result.childToolUsageNotes)
        }

        @Test
        fun `throws on object with no LlmTool methods`() {
            assertThrows<IllegalArgumentException> {
                UnfoldingTool.fromToolObject(
                    instance = NoLlmToolMethods(),
                    name = "empty",
                    description = "Empty",
                )
            }
        }

        @Test
        fun `works with interface implementation`() {
            val result = UnfoldingTool.fromToolObject(
                instance = ToolInterfaceImpl(),
                name = "interface_tools",
                description = "Tools from interface",
            )

            Assertions.assertEquals("interface_tools", result.definition.name)
            Assertions.assertEquals(2, result.innerTools.size)
            val toolNames = result.innerTools.map { it.definition.name }
            Assertions.assertTrue(toolNames.contains("interfaceSearch"))
            Assertions.assertTrue(toolNames.contains("interfaceCount"))
        }
    }

    @Nested
    inner class `withToolObject and UnfoldingTools annotation` {

        @Test
        fun `withToolObject with UnfoldingTools-annotated class adds as nested UnfoldingTool`() {
            val tool1 = MockTool("existing", "Existing tool") { Tool.Result.text("existing") }
            val initial = UnfoldingTool.of(
                name = "combined",
                description = "Combined tools",
                innerTools = listOf(tool1)
            )

            val combined = initial.withToolObject(UnfoldingAnnotatedTools())

            Assertions.assertEquals(2, combined.innerTools.size)
            Assertions.assertTrue(combined.innerTools.any { it.definition.name == "existing" })
            val nested = combined.innerTools.find { it.definition.name == "annotated_ops" }
            Assertions.assertNotNull(nested)
            Assertions.assertTrue(nested is UnfoldingTool)
        }

        @Test
        fun `Tool fromInstance with UnfoldingTools-annotated class returns single UnfoldingTool`() {
            val tools = Tool.fromInstance(UnfoldingAnnotatedTools())

            Assertions.assertEquals(1, tools.size)
            Assertions.assertTrue(tools[0] is UnfoldingTool)
            Assertions.assertEquals("annotated_ops", tools[0].definition.name)
            val unfolding = tools[0] as UnfoldingTool
            Assertions.assertEquals(2, unfolding.innerTools.size)
        }
    }
}

// Test fixture for builder tests
class BuilderTestTools {
    @LlmTool(description = "Search for items")
    fun builderSearch(query: String): String = "Found results for: $query"

    @LlmTool(description = "Filter results")
    fun builderFilter(criteria: String): String = "Filtered by: $criteria"
}

@UnfoldingTools(
    name = "database_operations",
    description = "Database operations. Invoke to see specific tools."
)
class SimpleDatabaseTools {

    @LlmTool(description = "Execute a SQL query")
    fun query(sql: String): String = "Query returned 5 rows"

    @LlmTool(description = "Insert a record")
    fun insert(table: String, data: String): String = "Inserted record with id 123"
}

@UnfoldingTools(
    name = "file_operations",
    description = "File operations. Pass category to select tools."
)
class CategoryBasedFileTools {

    @LlmTool(description = "Read file contents", category = "read")
    fun readFile(path: String): String = "file contents"

    @LlmTool(description = "List directory", category = "read")
    fun listDir(path: String): List<String> = listOf("file1.txt", "file2.txt")

    @LlmTool(description = "Write file", category = "write")
    fun writeFile(path: String, content: String): String = "Written"

    @LlmTool(description = "Delete file", category = "write")
    fun deleteFile(path: String): String = "Deleted"
}

@UnfoldingTools(
    name = "persistent_tools",
    description = "Persistent tools",
    removeOnInvoke = false
)
class PersistentTools {

    @LlmTool(description = "Do something")
    fun doSomething(): String = "done"
}

@UnfoldingTools(
    name = "music_search",
    description = "Search music database for artists, albums, and tracks",
    childToolUsageNotes = "Try vector search first for semantic queries. Use text search for exact artist names."
)
class MusicSearchTools {

    @LlmTool(description = "Semantic search using embeddings")
    fun vectorSearch(query: String): String = "Vector search results for: $query"

    @LlmTool(description = "Exact match text search")
    fun textSearch(query: String): String = "Text search results for: $query"
}

@UnfoldingTools(
    name = "mixed_tools",
    description = "Mixed category tools"
)
class MixedCategoryTools {

    @LlmTool(description = "Read operation", category = "read")
    fun readOp(): String = "read"

    @LlmTool(description = "Write operation", category = "write")
    fun writeOp(): String = "write"

    @LlmTool(description = "Always available tool")
    fun alwaysAvailable(): String = "available"
}

class NonAnnotatedClass {
    @LlmTool(description = "A tool")
    fun tool(): String = "result"
}

@UnfoldingTools(
    name = "empty",
    description = "Empty"
)
class NoToolMethods {
    fun notATool(): String = "not a tool"
}

/**
 * Level 3 - deepest level with actual tools
 */
@UnfoldingTools(
    name = "level3_tools",
    description = "Level 3 tools - the actual operations"
)
class Level3Tools {

    @LlmTool(description = "Execute a query")
    fun query(sql: String): String = "Query result: $sql"

    @LlmTool(description = "Insert data")
    fun insert(table: String): String = "Inserted into $table"
}

/**
 * Level 2 - contains Level 3 as an inner class
 */
@UnfoldingTools(
    name = "level2_category",
    description = "Level 2 category - contains Level 3"
)
class Level2Category {

    @LlmTool(description = "Level 2 utility function")
    fun level2Util(): String = "Level 2 utility"

    @UnfoldingTools(
        name = "level3_inner",
        description = "Inner Level 3 tools"
    )
    class Level3Inner {

        @LlmTool(description = "Inner query")
        fun innerQuery(): String = "Inner query result"

        @LlmTool(description = "Inner insert")
        fun innerInsert(): String = "Inner insert result"
    }
}

/**
 * Level 1 - top level that contains Level 2
 */
@UnfoldingTools(
    name = "level1_top",
    description = "Top level - invoke to access Level 2"
)
class Level1Top {

    @LlmTool(description = "Top level status")
    fun status(): String = "System status: OK"

    @UnfoldingTools(
        name = "level2_inner",
        description = "Inner Level 2 category"
    )
    class Level2Inner {

        @LlmTool(description = "Level 2 inner operation")
        fun level2Op(): String = "Level 2 operation"

        @UnfoldingTools(
            name = "level3_deepest",
            description = "Deepest Level 3 tools"
        )
        class Level3Deepest {

            @LlmTool(description = "Deepest query")
            fun deepQuery(): String = "Deep query result"

            @LlmTool(description = "Deepest mutation")
            fun deepMutate(): String = "Deep mutation result"
        }
    }
}

@UnfoldingTools(
    name = "annotated_ops",
    description = "Operations using @UnfoldingTools annotation"
)
class UnfoldingAnnotatedTools {

    @LlmTool(description = "Search items")
    fun search(query: String): String = "Results for: $query"

    @LlmTool(description = "Count items")
    fun count(): String = "42"
}

class PlainToolMethods {
    @LlmTool(description = "Search for items")
    fun doSearch(query: String): String = "Results for: $query"

    @LlmTool(description = "Filter results")
    fun doFilter(criteria: String): String = "Filtered by: $criteria"
}

class NoLlmToolMethods {
    fun regularMethod(): String = "not a tool"
}

interface ToolInterface {
    @LlmTool(description = "Search via interface")
    fun interfaceSearch(query: String): String = "Found: $query"

    @LlmTool(description = "Count via interface")
    fun interfaceCount(): String = "42"
}

class ToolInterfaceImpl : ToolInterface
