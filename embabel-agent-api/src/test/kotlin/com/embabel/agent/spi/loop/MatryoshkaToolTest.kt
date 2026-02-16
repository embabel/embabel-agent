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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.MatryoshkaTools
import com.embabel.agent.api.annotation.UnfoldingTools
import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.chat.UserMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

        @Test
        fun `of creates tool with childToolUsageNotes`() {
            val innerTool = MockTool("search", "Search") { Tool.Result.text("results") }

            val matryoshka = MatryoshkaTool.of(
                name = "data_tools",
                description = "Tools for data access",
                innerTools = listOf(innerTool),
                childToolUsageNotes = "Try semantic search first before falling back to keyword search.",
            )

            assertEquals("data_tools", matryoshka.definition.name)
            assertEquals(
                "Try semantic search first before falling back to keyword search.",
                matryoshka.childToolUsageNotes
            )
        }

        @Test
        fun `childToolUsageNotes defaults to null when not specified`() {
            val innerTool = MockTool("tool", "Tool") { Tool.Result.text("result") }

            val matryoshka = MatryoshkaTool.of(
                name = "simple",
                description = "Simple tool",
                innerTools = listOf(innerTool),
            )

            assertNull(matryoshka.childToolUsageNotes)
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
    inner class UnfoldingToolInjectionStrategyTest {

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

            val strategy = UnfoldingToolInjectionStrategy()
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            assertTrue(result.hasChanges())
            // Inner tool + context tool
            assertEquals(2, result.toolsToAdd.size)
            assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            assertTrue(result.toolsToAdd.any { it.definition.name == "outer_context" })
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            assertTrue(result.hasChanges())
            // Inner tool + context tool
            assertEquals(2, result.toolsToAdd.size)
            assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            assertTrue(result.toolsToAdd.any { it.definition.name == "persistent_context" })
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // Selected tool + context tool
            assertEquals(2, result.toolsToAdd.size)
            assertTrue(result.toolsToAdd.any { it.definition.name == "tool1" })
            assertTrue(result.toolsToAdd.any { it.definition.name == "selector_context" })
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // Should still remove the tool, just with no additions
            assertTrue(result.toolsToRemove.isNotEmpty())
            assertTrue(result.toolsToAdd.isEmpty())
        }

        @Test
        fun `strategy injects context tool alongside inner tools`() {
            val innerTool1 = MockTool("count", "Count records") { Tool.Result.text("5") }
            val innerTool2 = MockTool("getValues", "Get distinct values") { Tool.Result.text("[]") }
            val matryoshka = MatryoshkaTool.of(
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

            // Should inject inner tools + context tool
            assertEquals(3, result.toolsToAdd.size)

            val contextTool = result.toolsToAdd.find { it.definition.name == "composer_stats_context" }
            assertNotNull(contextTool, "Context tool should be injected")
        }

        @Test
        fun `context tool description contains parent description and tool names`() {
            val innerTool1 = MockTool("count", "Count records") { Tool.Result.text("5") }
            val innerTool2 = MockTool("getValues", "Get distinct values") { Tool.Result.text("[]") }
            val matryoshka = MatryoshkaTool.of(
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

            val contextTool = result.toolsToAdd.find { it.definition.name == "composer_stats_context" }!!
            val description = contextTool.definition.description

            // Should contain parent description
            assertTrue(
                description.contains("Use this to find stats about composers"),
                "Context tool description should contain parent description"
            )
            // Should list available tools
            assertTrue(description.contains("count"), "Description should list count tool")
            assertTrue(description.contains("getValues"), "Description should list getValues tool")
        }

        @Test
        fun `context tool returns full details about child tools when called`() {
            val innerTool1 = MockTool("count", "Count records matching criteria") { Tool.Result.text("5") }
            val innerTool2 = MockTool("getValues", "Get distinct values for a field") { Tool.Result.text("[]") }
            val matryoshka = MatryoshkaTool.of(
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

            val contextTool = result.toolsToAdd.find { it.definition.name == "composer_stats_context" }!!
            val callResult = contextTool.call("{}")

            assertTrue(callResult is Tool.Result.Text)
            val content = (callResult as Tool.Result.Text).content

            // Should contain full details about each tool
            assertTrue(content.contains("count"), "Should mention count tool")
            assertTrue(
                content.contains("Count records matching criteria"),
                "Should include count tool's full description"
            )
            assertTrue(content.contains("getValues"), "Should mention getValues tool")
            assertTrue(
                content.contains("Get distinct values for a field"),
                "Should include getValues tool's full description"
            )
        }

        @Test
        fun `context tool not injected when no inner tools selected`() {
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

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            // No context tool when no inner tools
            assertTrue(result.toolsToAdd.isEmpty())
        }

        @Test
        fun `context tool includes childToolUsageNotes in description`() {
            val vectorSearch = MockTool("vector_search", "Semantic search") { Tool.Result.text("[]") }
            val textSearch = MockTool("text_search", "Exact match search") { Tool.Result.text("[]") }
            val matryoshka = MatryoshkaTool.of(
                name = "spotify_search",
                description = "Search Spotify for music data",
                innerTools = listOf(vectorSearch, textSearch),
                childToolUsageNotes = "Try vector search first for semantic queries. Use text search for exact matches.",
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "spotify_search",
                    toolInput = "{}",
                    result = "Enabled 2 tools: vector_search, text_search",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            val contextTool = result.toolsToAdd.find { it.definition.name == "spotify_search_context" }!!
            val description = contextTool.definition.description

            // Should contain parent description, tool list, and usage notes
            assertTrue(
                description.contains("Search Spotify for music data"),
                "Context tool description should contain parent description"
            )
            assertTrue(description.contains("vector_search"), "Description should list vector_search")
            assertTrue(description.contains("text_search"), "Description should list text_search")
            assertTrue(
                description.contains("Try vector search first"),
                "Description should include childToolUsageNotes"
            )
        }

        @Test
        fun `context tool returns usage notes when called`() {
            val vectorSearch = MockTool("vector_search", "Semantic search using embeddings") { Tool.Result.text("[]") }
            val textSearch = MockTool("text_search", "Exact match text search") { Tool.Result.text("[]") }
            val matryoshka = MatryoshkaTool.of(
                name = "music_search",
                description = "Search music database",
                innerTools = listOf(vectorSearch, textSearch),
                childToolUsageNotes = "Prefer vector search for natural language queries.",
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "music_search",
                    toolInput = "{}",
                    result = "Enabled 2 tools",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            val contextTool = result.toolsToAdd.find { it.definition.name == "music_search_context" }!!
            val callResult = contextTool.call("{}")

            assertTrue(callResult is Tool.Result.Text)
            val content = (callResult as Tool.Result.Text).content

            // Should contain tool details and usage notes
            assertTrue(content.contains("vector_search"), "Should mention vector_search")
            assertTrue(content.contains("Semantic search using embeddings"), "Should include tool description")
            assertTrue(content.contains("Prefer vector search"), "Should include usage notes when called")
        }

        @Test
        fun `context tool omits usage notes section when childToolUsageNotes is null`() {
            val tool1 = MockTool("tool1", "First tool") { Tool.Result.text("1") }
            val matryoshka = MatryoshkaTool.of(
                name = "no_notes",
                description = "Tools without usage notes",
                innerTools = listOf(tool1),
                // childToolUsageNotes not specified, defaults to null
            )

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "no_notes",
                    toolInput = "{}",
                    result = "Enabled 1 tool",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            val contextTool = result.toolsToAdd.find { it.definition.name == "no_notes_context" }!!
            val callResult = contextTool.call("{}")

            assertTrue(callResult is Tool.Result.Text)
            val content = (callResult as Tool.Result.Text).content

            // Should NOT contain "Usage notes:" section
            assertFalse(
                content.contains("Usage notes:"),
                "Should not include usage notes section when childToolUsageNotes is null"
            )
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

            // Inner tool + context tool
            assertEquals(2, result.toolsToAdd.size)
            assertTrue(result.toolsToAdd.any { it.definition.name == "inner" })
            assertTrue(result.toolsToAdd.any { it.definition.name == "outer_context" })
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
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Query the database")),
                initialTools = listOf(matryoshka),
                outputParser = { it }
            )

            assertEquals("Found 5 rows in the database.", result.result)
            // Inner tool + context tool
            assertEquals(2, result.injectedTools.size)
            assertTrue(result.injectedTools.any { it.definition.name == "query" })
            assertTrue(result.injectedTools.any { it.definition.name == "database_context" })
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
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                injectionStrategy = UnfoldingToolInjectionStrategy.INSTANCE,
            )

            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("Drill down")),
                initialTools = listOf(outerMatryoshka),
                outputParser = { it }
            )

            assertEquals("Got leaf result", result.result)
            // Both matryoshkas inject their tools + context tools:
            // outer injects: inner_category + outer_category_context
            // inner injects: leaf + inner_category_context
            assertEquals(4, result.injectedTools.size)
            assertTrue(result.injectedTools.any { it.definition.name == "inner_category" })
            assertTrue(result.injectedTools.any { it.definition.name == "outer_category_context" })
            assertTrue(result.injectedTools.any { it.definition.name == "leaf" })
            assertTrue(result.injectedTools.any { it.definition.name == "inner_category_context" })
            // Both outer and inner matryoshkas were removed
            assertEquals(2, result.removedTools.size)
        }
    }

    @Nested
    inner class AnnotationBasedMatryoshkaToolTest {

        @Test
        fun `fromInstance creates simple MatryoshkaTool from annotated class`() {
            val matryoshka = MatryoshkaTool.fromInstance(SimpleDatabaseTools())

            assertEquals("database_operations", matryoshka.definition.name)
            assertEquals("Database operations. Invoke to see specific tools.", matryoshka.definition.description)
            assertEquals(2, matryoshka.innerTools.size)
            assertTrue(matryoshka.removeOnInvoke)

            val toolNames = matryoshka.innerTools.map { it.definition.name }
            assertTrue(toolNames.contains("query"))
            assertTrue(toolNames.contains("insert"))
        }

        @Test
        fun `fromInstance creates category-based MatryoshkaTool when categories are used`() {
            val matryoshka = MatryoshkaTool.fromInstance(CategoryBasedFileTools())

            assertEquals("file_operations", matryoshka.definition.name)
            assertEquals(4, matryoshka.innerTools.size) // 2 read + 2 write

            // Verify category selection works
            val readTools = matryoshka.selectTools("""{"category": "read"}""")
            assertEquals(2, readTools.size)
            assertTrue(readTools.all { it.definition.name in listOf("readFile", "listDir") })

            val writeTools = matryoshka.selectTools("""{"category": "write"}""")
            assertEquals(2, writeTools.size)
            assertTrue(writeTools.all { it.definition.name in listOf("writeFile", "deleteFile") })
        }

        @Test
        fun `fromInstance respects removeOnInvoke annotation attribute`() {
            val matryoshka = MatryoshkaTool.fromInstance(PersistentTools())

            assertEquals("persistent_tools", matryoshka.definition.name)
            assertFalse(matryoshka.removeOnInvoke)
        }

        @Test
        fun `fromInstance respects childToolUsageNotes annotation attribute`() {
            val matryoshka = MatryoshkaTool.fromInstance(MusicSearchTools())

            assertEquals("music_search", matryoshka.definition.name)
            assertEquals("Search music database for artists, albums, and tracks", matryoshka.definition.description)
            assertEquals(
                "Try vector search first for semantic queries. Use text search for exact artist names.",
                matryoshka.childToolUsageNotes
            )
        }

        @Test
        fun `childToolUsageNotes from annotation appears in injected context tool`() {
            val matryoshka = MatryoshkaTool.fromInstance(MusicSearchTools())

            val context = ToolInjectionContext(
                conversationHistory = emptyList(),
                currentTools = listOf(matryoshka),
                lastToolCall = ToolCallResult(
                    toolName = "music_search",
                    toolInput = "{}",
                    result = "Enabled 2 tools",
                    resultObject = null,
                ),
                iterationCount = 1,
            )

            val strategy = UnfoldingToolInjectionStrategy()
            val result = strategy.evaluate(context)

            val contextTool = result.toolsToAdd.find { it.definition.name == "music_search_context" }!!

            // Description should include parent description and usage notes
            val description = contextTool.definition.description
            assertTrue(
                description.contains("Search music database"),
                "Description should include parent description"
            )
            assertTrue(
                description.contains("Try vector search first"),
                "Description should include childToolUsageNotes"
            )

            // When called, should return full details with usage notes
            val callResult = contextTool.call("{}")
            val content = (callResult as Tool.Result.Text).content
            assertTrue(
                content.contains("Usage notes:"),
                "Call result should include usage notes section"
            )
            assertTrue(
                content.contains("Try vector search first"),
                "Call result should include the actual usage notes"
            )
        }

        @Test
        fun `fromInstance throws for class without MatryoshkaTools annotation`() {
            val exception = assertThrows<IllegalArgumentException> {
                MatryoshkaTool.fromInstance(NonAnnotatedClass())
            }
            assertTrue(exception.message!!.contains("not annotated with @MatryoshkaTools"))
        }

        @Test
        fun `fromInstance throws for class without LlmTool methods`() {
            val exception = assertThrows<IllegalArgumentException> {
                MatryoshkaTool.fromInstance(NoToolMethods())
            }
            assertTrue(exception.message!!.contains("no methods annotated with @LlmTool"))
        }

        @Test
        fun `safelyFromInstance returns null for non-annotated class`() {
            val result = MatryoshkaTool.safelyFromInstance(NonAnnotatedClass())
            assertNull(result)
        }

        @Test
        fun `safelyFromInstance returns MatryoshkaTool for valid class`() {
            val result = MatryoshkaTool.safelyFromInstance(SimpleDatabaseTools())
            assertNotNull(result)
            assertEquals("database_operations", result!!.definition.name)
        }

        @Test
        fun `category-based MatryoshkaTool includes uncategorized tools in all category`() {
            val matryoshka = MatryoshkaTool.fromInstance(MixedCategoryTools())

            // The "all" category should include everything
            val allTools = matryoshka.selectTools("""{"category": "all"}""")
            assertEquals(3, allTools.size)

            // Read category should have read tool + uncategorized tool
            val readTools = matryoshka.selectTools("""{"category": "read"}""")
            assertEquals(2, readTools.size)
        }

        @Test
        fun `tools from annotated class are callable`() {
            val matryoshka = MatryoshkaTool.fromInstance(SimpleDatabaseTools())

            val queryTool = matryoshka.innerTools.find { it.definition.name == "query" }!!
            val result = queryTool.call("""{"sql": "SELECT * FROM users"}""")

            assertTrue(result is Tool.Result.Text)
            assertTrue((result as Tool.Result.Text).content.contains("5 rows"))
        }

        @Test
        fun `Tool_fromInstance returns MatryoshkaTool when class has MatryoshkaTools annotation`() {
            val tools = Tool.fromInstance(SimpleDatabaseTools())

            assertEquals(1, tools.size)
            assertTrue(tools[0] is MatryoshkaTool)
            assertEquals("database_operations", tools[0].definition.name)

            val matryoshka = tools[0] as MatryoshkaTool
            assertEquals(2, matryoshka.innerTools.size)
        }

        @Test
        fun `Tool_fromInstance returns individual tools when class lacks MatryoshkaTools annotation`() {
            val tools = Tool.fromInstance(NonAnnotatedClass())

            assertEquals(1, tools.size)
            assertFalse(tools[0] is MatryoshkaTool)
            assertEquals("tool", tools[0].definition.name)
        }

        @Test
        fun `Tool_safelyFromInstance returns MatryoshkaTool when class has MatryoshkaTools annotation`() {
            val tools = Tool.safelyFromInstance(SimpleDatabaseTools())

            assertEquals(1, tools.size)
            assertTrue(tools[0] is MatryoshkaTool)
        }
    }

    @Nested
    inner class ConfiguredInnerToolsTest {

        @Test
        fun `MatryoshkaTool can pass parameters to configure inner tools`() {
            // Create a MatryoshkaTool that creates configured instances based on input
            val matryoshka = MatryoshkaTool.selectable(
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
            assertEquals(2, prodTools.size)
            assertTrue(prodTools[0].definition.description.contains("prod-db.example.com"))

            val devTools = matryoshka.selectTools("""{"connection": "localhost:5432"}""")
            assertEquals(2, devTools.size)
            assertTrue(devTools[0].definition.description.contains("localhost:5432"))

            // Verify the tools are callable with the configured connection
            val result = prodTools[0].call("{}")
            assertTrue((result as Tool.Result.Text).content.contains("prod-db.example.com"))
        }

        @Test
        fun `tool loop uses configured inner tools from MatryoshkaTool parameters`() {
            // Create a MatryoshkaTool that configures tools based on user selection
            var capturedRegion = ""
            val regionTool = MatryoshkaTool.selectable(
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

            assertEquals("Deployment complete", result.result)
            assertEquals("eu-west", capturedRegion)
            // Verify the injected tool was configured with the region
            val deployTool = result.injectedTools.find { it.definition.name == "deploy" }
            assertNotNull(deployTool)
            assertTrue(deployTool!!.definition.description.contains("eu-west"))
        }

        @Test
        fun `MatryoshkaTool can create stateful tool instances`() {
            // Create a MatryoshkaTool that creates tools with captured state
            val matryoshka = MatryoshkaTool.selectable(
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
            assertEquals(2, cartTools.size)

            // Add items and verify state is maintained
            val addTool = cartTools.find { it.definition.name == "add_item" }!!
            val getTool = cartTools.find { it.definition.name == "get_cart" }!!

            addTool.call("""{"item": "apple"}""")
            addTool.call("""{"item": "banana"}""")

            val result = getTool.call("{}")
            val content = (result as Tool.Result.Text).content
            assertTrue(content.contains("apple"))
            assertTrue(content.contains("banana"))
            assertTrue(content.contains("cart-123"))
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
            val level2 = MatryoshkaTool.of(
                name = "level2_database",
                description = "Database operations",
                innerTools = listOf(leafTool1, leafTool2),
            )

            // Level 1 - contains level 2
            val level1 = MatryoshkaTool.of(
                name = "level1_admin",
                description = "Admin operations",
                innerTools = listOf(level2),
            )

            // Verify structure
            assertEquals("level1_admin", level1.definition.name)
            assertEquals(1, level1.innerTools.size)

            val innerLevel2 = level1.innerTools[0] as MatryoshkaTool
            assertEquals("level2_database", innerLevel2.definition.name)
            assertEquals(2, innerLevel2.innerTools.size)
        }

        @Test
        fun `five level nesting with programmatic interface`() {
            // Level 5 - deepest leaf tools
            val leaf1 = MockTool("deep_read", "Read data") { Tool.Result.text("read") }
            val leaf2 = MockTool("deep_write", "Write data") { Tool.Result.text("write") }

            // Level 4
            val level4 = MatryoshkaTool.of(
                name = "level4_io",
                description = "I/O operations",
                innerTools = listOf(leaf1, leaf2),
            )

            // Level 3
            val level3 = MatryoshkaTool.of(
                name = "level3_storage",
                description = "Storage operations",
                innerTools = listOf(level4),
            )

            // Level 2
            val level2 = MatryoshkaTool.of(
                name = "level2_data",
                description = "Data operations",
                innerTools = listOf(level3),
            )

            // Level 1 - top
            val level1 = MatryoshkaTool.of(
                name = "level1_root",
                description = "Root operations",
                innerTools = listOf(level2),
            )

            // Verify the chain
            assertEquals("level1_root", level1.definition.name)
            val l2 = level1.innerTools[0] as MatryoshkaTool
            assertEquals("level2_data", l2.definition.name)
            val l3 = l2.innerTools[0] as MatryoshkaTool
            assertEquals("level3_storage", l3.definition.name)
            val l4 = l3.innerTools[0] as MatryoshkaTool
            assertEquals("level4_io", l4.definition.name)
            assertEquals(2, l4.innerTools.size)
            assertEquals("deep_read", l4.innerTools[0].definition.name)
            assertEquals("deep_write", l4.innerTools[1].definition.name)
        }

        @Test
        fun `tool loop handles five level nesting`() {
            // Build 5-level hierarchy
            val leaf = MockTool("leaf", "Leaf tool") { Tool.Result.text("leaf result") }
            val level4 = MatryoshkaTool.of("level4", "Level 4", listOf(leaf))
            val level3 = MatryoshkaTool.of("level3", "Level 3", listOf(level4))
            val level2 = MatryoshkaTool.of("level2", "Level 2", listOf(level3))
            val level1 = MatryoshkaTool.of("level1", "Level 1", listOf(level2))

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

            assertEquals("Traversed 5 levels to reach leaf", result.result)
            // 4 matryoshka tools removed (level1-4)
            assertEquals(4, result.removedTools.size)
            // 8 injected: 4 inner tools (level2-4 + leaf) + 4 context tools
            assertEquals(8, result.injectedTools.size)
            assertTrue(result.injectedTools.any { it.definition.name == "leaf" })
            assertTrue(result.injectedTools.any { it.definition.name == "level1_context" })
            assertTrue(result.injectedTools.any { it.definition.name == "level4_context" })
        }

        @Test
        fun `mixed nesting with multiple branches at each level`() {
            // Level 2 branches
            val branch1Leaf = MockTool("b1_leaf", "Branch 1 leaf") { Tool.Result.text("b1") }
            val branch2Leaf = MockTool("b2_leaf", "Branch 2 leaf") { Tool.Result.text("b2") }

            val branch1 = MatryoshkaTool.of(
                name = "branch1",
                description = "Branch 1",
                innerTools = listOf(branch1Leaf),
            )

            val branch2 = MatryoshkaTool.of(
                name = "branch2",
                description = "Branch 2",
                innerTools = listOf(branch2Leaf),
            )

            // Level 1 with two branches
            val level1 = MatryoshkaTool.of(
                name = "root",
                description = "Root with branches",
                innerTools = listOf(branch1, branch2),
            )

            // Verify structure
            assertEquals(2, level1.innerTools.size)
            assertTrue(level1.innerTools[0] is MatryoshkaTool)
            assertTrue(level1.innerTools[1] is MatryoshkaTool)

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

            assertEquals("Used branch 1", result.result)
            // root and branch1 removed, branch1+branch2+b1_leaf injected
            assertEquals(2, result.removedTools.size)
            assertTrue(result.removedTools.any { it.definition.name == "root" })
            assertTrue(result.removedTools.any { it.definition.name == "branch1" })
        }
    }

    @Nested
    inner class DeepNestingAnnotationTest {

        @Test
        fun `creates MatryoshkaTool with nested inner class MatryoshkaTools`() {
            val matryoshka = MatryoshkaTool.fromInstance(Level2Category())

            assertEquals("level2_category", matryoshka.definition.name)
            // Should have 1 direct tool + 1 inner MatryoshkaTool
            assertEquals(2, matryoshka.innerTools.size)

            val directTool = matryoshka.innerTools.find { it.definition.name == "level2Util" }
            assertNotNull(directTool)
            assertFalse(directTool is MatryoshkaTool)

            val innerMatryoshka = matryoshka.innerTools.find { it.definition.name == "level3_inner" }
            assertNotNull(innerMatryoshka)
            assertTrue(innerMatryoshka is MatryoshkaTool)

            val level3 = innerMatryoshka as MatryoshkaTool
            assertEquals(2, level3.innerTools.size)
            assertTrue(level3.innerTools.any { it.definition.name == "innerQuery" })
            assertTrue(level3.innerTools.any { it.definition.name == "innerInsert" })
        }

        @Test
        fun `creates three level deep MatryoshkaTool hierarchy from nested annotations`() {
            val level1 = MatryoshkaTool.fromInstance(Level1Top())

            assertEquals("level1_top", level1.definition.name)
            // 1 direct tool (status) + 1 inner MatryoshkaTool (level2_inner)
            assertEquals(2, level1.innerTools.size)

            val statusTool = level1.innerTools.find { it.definition.name == "status" }
            assertNotNull(statusTool)

            val level2 = level1.innerTools.find { it.definition.name == "level2_inner" } as? MatryoshkaTool
            assertNotNull(level2)
            // 1 direct tool (level2Op) + 1 inner MatryoshkaTool (level3_deepest)
            assertEquals(2, level2!!.innerTools.size)

            val level2Op = level2.innerTools.find { it.definition.name == "level2Op" }
            assertNotNull(level2Op)

            val level3 = level2.innerTools.find { it.definition.name == "level3_deepest" } as? MatryoshkaTool
            assertNotNull(level3)
            assertEquals(2, level3!!.innerTools.size)
            assertTrue(level3.innerTools.any { it.definition.name == "deepQuery" })
            assertTrue(level3.innerTools.any { it.definition.name == "deepMutate" })
        }

        @Test
        fun `tool loop traverses annotation-based nested hierarchy`() {
            val level1 = MatryoshkaTool.fromInstance(Level1Top())

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

            assertEquals("Executed deep query", result.result)
            // All 3 matryoshka levels removed
            assertEquals(3, result.removedTools.size)
            assertTrue(result.removedTools.any { it.definition.name == "level1_top" })
            assertTrue(result.removedTools.any { it.definition.name == "level2_inner" })
            assertTrue(result.removedTools.any { it.definition.name == "level3_deepest" })
        }

        @Test
        fun `inner tools from nested annotations are callable`() {
            val level1 = MatryoshkaTool.fromInstance(Level1Top())

            // Get level2
            val level2 = level1.innerTools.find { it.definition.name == "level2_inner" } as MatryoshkaTool

            // Get level3
            val level3 = level2.innerTools.find { it.definition.name == "level3_deepest" } as MatryoshkaTool

            // Call the deepest tool
            val deepQueryTool = level3.innerTools.find { it.definition.name == "deepQuery" }!!
            val result = deepQueryTool.call("{}")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Deep query result", (result as Tool.Result.Text).content)
        }

        @Test
        fun `Tool_fromInstance detects nested MatryoshkaTools in inner classes`() {
            val tools = Tool.fromInstance(Level1Top())

            assertEquals(1, tools.size)
            assertTrue(tools[0] is MatryoshkaTool)

            val level1 = tools[0] as MatryoshkaTool
            assertEquals("level1_top", level1.definition.name)

            // Verify nested structure
            val level2 = level1.innerTools.find { it is MatryoshkaTool && it.definition.name == "level2_inner" }
            assertNotNull(level2)
        }
    }

    @Nested
    inner class InjectedToolDecorationTest {

        /**
         * Verifies that when DefaultToolLoop has a toolDecorator,
         * injected tools from MatryoshkaTool are decorated.
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

            val matryoshka = MatryoshkaTool.of(
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
            assertTrue(
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

            val matryoshka = MatryoshkaTool.of(
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
            assertTrue(
                injectedTool is DecoratedTool,
                "Injected tool should be decorated"
            )
        }

        /**
         * Verifies that nested MatryoshkaTool child tools are all decorated.
         */
        @Test
        fun `nested MatryoshkaTool child tools are all decorated`() {
            val decoratedToolNames = mutableListOf<String>()

            val leafTool = MockTool("leaf", "Leaf tool") {
                Tool.Result.text("leaf result")
            }

            val innerMatryoshka = MatryoshkaTool.of(
                name = "inner",
                description = "Inner category",
                innerTools = listOf(leafTool),
            )

            val outerMatryoshka = MatryoshkaTool.of(
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
            assertTrue(decoratedToolNames.contains("inner"), "inner should be decorated")
            assertTrue(decoratedToolNames.contains("leaf"), "leaf should be decorated")
        }
    }

    @Nested
    inner class UnfoldingToolBuilderTests {

        @Test
        fun `withTools adds tools to existing UnfoldingTool`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }
            val tool3 = MockTool("tool3", "Tool 3") { Tool.Result.text("3") }

            val initial = com.embabel.agent.api.tool.progressive.UnfoldingTool.of(
                name = "combined",
                description = "Combined tools",
                innerTools = listOf(tool1)
            )

            val combined = initial.withTools(tool2, tool3)

            assertEquals("combined", combined.definition.name)
            assertEquals(3, combined.innerTools.size)
            assertTrue(combined.innerTools.any { it.definition.name == "tool1" })
            assertTrue(combined.innerTools.any { it.definition.name == "tool2" })
            assertTrue(combined.innerTools.any { it.definition.name == "tool3" })
        }

        @Test
        fun `withTools preserves other properties`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val initial = com.embabel.agent.api.tool.progressive.UnfoldingTool.of(
                name = "mytools",
                description = "My tools description",
                innerTools = listOf(tool1),
                removeOnInvoke = false,
                childToolUsageNotes = "Use tool1 for primary operations"
            )

            val combined = initial.withTools(tool2)

            assertEquals("mytools", combined.definition.name)
            assertEquals("My tools description", combined.definition.description)
            assertEquals(false, combined.removeOnInvoke)
            assertEquals("Use tool1 for primary operations", combined.childToolUsageNotes)
        }

        @Test
        fun `withToolObject adds tools from annotated object`() {
            val tool1 = MockTool("existing", "Existing tool") { Tool.Result.text("existing") }

            val initial = com.embabel.agent.api.tool.progressive.UnfoldingTool.of(
                name = "combined",
                description = "Combined tools",
                innerTools = listOf(tool1)
            )

            val combined = initial.withToolObject(BuilderTestTools())

            assertEquals(3, combined.innerTools.size)
            assertTrue(combined.innerTools.any { it.definition.name == "existing" })
            assertTrue(combined.innerTools.any { it.definition.name == "builderSearch" })
            assertTrue(combined.innerTools.any { it.definition.name == "builderFilter" })
        }

        @Test
        fun `withToolObject preserves properties`() {
            val initial = com.embabel.agent.api.tool.progressive.UnfoldingTool.of(
                name = "mytools",
                description = "My description",
                innerTools = emptyList(),
                removeOnInvoke = false,
                childToolUsageNotes = "Custom notes"
            )

            val combined = initial.withToolObject(BuilderTestTools())

            assertEquals("mytools", combined.definition.name)
            assertEquals("My description", combined.definition.description)
            assertEquals(false, combined.removeOnInvoke)
            assertEquals("Custom notes", combined.childToolUsageNotes)
        }

        @Test
        fun `chaining withTools and withToolObject`() {
            val tool1 = MockTool("tool1", "Tool 1") { Tool.Result.text("1") }
            val tool2 = MockTool("tool2", "Tool 2") { Tool.Result.text("2") }

            val initial = com.embabel.agent.api.tool.progressive.UnfoldingTool.of(
                name = "chained",
                description = "Chained tools",
                innerTools = listOf(tool1)
            )

            val combined = initial
                .withTools(tool2)
                .withToolObject(BuilderTestTools())

            assertEquals(4, combined.innerTools.size)
            assertTrue(combined.innerTools.any { it.definition.name == "tool1" })
            assertTrue(combined.innerTools.any { it.definition.name == "tool2" })
            assertTrue(combined.innerTools.any { it.definition.name == "builderSearch" })
            assertTrue(combined.innerTools.any { it.definition.name == "builderFilter" })
        }

        @Test
        fun `tools added via withToolObject are callable`() {
            val initial = com.embabel.agent.api.tool.progressive.UnfoldingTool.of(
                name = "test",
                description = "Test",
                innerTools = emptyList()
            )

            val combined = initial.withToolObject(BuilderTestTools())

            val searchTool = combined.innerTools.find { it.definition.name == "builderSearch" }!!
            val result = searchTool.call("""{"query": "test query"}""")

            assertTrue(result is Tool.Result.Text)
            assertTrue((result as Tool.Result.Text).content.contains("test query"))
        }
    }

    @Nested
    inner class `withToolObject and UnfoldingTools annotation` {

        @Test
        fun `withToolObject with UnfoldingTools-annotated class adds as nested UnfoldingTool`() {
            val tool1 = MockTool("existing", "Existing tool") { Tool.Result.text("existing") }
            val initial = com.embabel.agent.api.tool.progressive.UnfoldingTool.of(
                name = "combined",
                description = "Combined tools",
                innerTools = listOf(tool1)
            )

            val combined = initial.withToolObject(UnfoldingAnnotatedTools())

            assertEquals(2, combined.innerTools.size)
            assertTrue(combined.innerTools.any { it.definition.name == "existing" })
            val nested = combined.innerTools.find { it.definition.name == "annotated_ops" }
            assertNotNull(nested)
            assertTrue(nested is com.embabel.agent.api.tool.progressive.UnfoldingTool)
        }

        @Test
        fun `Tool fromInstance with UnfoldingTools-annotated class returns single UnfoldingTool`() {
            val tools = Tool.fromInstance(UnfoldingAnnotatedTools())

            assertEquals(1, tools.size)
            assertTrue(tools[0] is com.embabel.agent.api.tool.progressive.UnfoldingTool)
            assertEquals("annotated_ops", tools[0].definition.name)
            val unfolding = tools[0] as com.embabel.agent.api.tool.progressive.UnfoldingTool
            assertEquals(2, unfolding.innerTools.size)
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

// Test fixture classes

@MatryoshkaTools(
    name = "database_operations",
    description = "Database operations. Invoke to see specific tools."
)
class SimpleDatabaseTools {

    @LlmTool(description = "Execute a SQL query")
    fun query(sql: String): String = "Query returned 5 rows"

    @LlmTool(description = "Insert a record")
    fun insert(table: String, data: String): String = "Inserted record with id 123"
}

@MatryoshkaTools(
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

@MatryoshkaTools(
    name = "persistent_tools",
    description = "Persistent tools",
    removeOnInvoke = false
)
class PersistentTools {

    @LlmTool(description = "Do something")
    fun doSomething(): String = "done"
}

@MatryoshkaTools(
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

@MatryoshkaTools(
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

@MatryoshkaTools(
    name = "empty",
    description = "Empty"
)
class NoToolMethods {
    fun notATool(): String = "not a tool"
}

/**
 * Level 3 - deepest level with actual tools
 */
@MatryoshkaTools(
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
@MatryoshkaTools(
    name = "level2_category",
    description = "Level 2 category - contains Level 3"
)
class Level2Category {

    @LlmTool(description = "Level 2 utility function")
    fun level2Util(): String = "Level 2 utility"

    @MatryoshkaTools(
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
@MatryoshkaTools(
    name = "level1_top",
    description = "Top level - invoke to access Level 2"
)
class Level1Top {

    @LlmTool(description = "Top level status")
    fun status(): String = "System status: OK"

    @MatryoshkaTools(
        name = "level2_inner",
        description = "Inner Level 2 category"
    )
    class Level2Inner {

        @LlmTool(description = "Level 2 inner operation")
        fun level2Op(): String = "Level 2 operation"

        @MatryoshkaTools(
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
