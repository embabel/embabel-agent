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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.model.ModelOptionsUtils
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.metadata.DefaultToolMetadata

class SpringToolCallbackAdapterTest {

    @Nested
    inner class ToolToSpringAdapter {

        @Test
        fun `adapter converts tool definition correctly`() {
            val tool = Tool.of(
                name = "test_tool",
                description = "A test tool",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("input", Tool.ParameterType.STRING, "Input value"),
                ),
            ) { _ ->
                Tool.Result.text("result")
            }

            val callback = SpringToolCallbackAdapter(tool)
            val definition = callback.toolDefinition

            assertEquals("test_tool", definition.name())
            assertEquals("A test tool", definition.description())
            assertTrue(definition.inputSchema()!!.contains("\"input\""))
            assertTrue(definition.inputSchema()!!.contains("\"type\":\"string\""))
        }

        @Test
        fun `adapter converts metadata with returnDirect`() {
            val tool = Tool.of(
                name = "direct_tool",
                description = "Returns directly",
                metadata = Tool.Metadata(returnDirect = true),
            ) { _ ->
                Tool.Result.text("result")
            }

            val callback = SpringToolCallbackAdapter(tool)
            val metadata = callback.toolMetadata

            assertTrue(metadata.returnDirect())
        }

        @Test
        fun `adapter executes tool and returns text result`() {
            val tool = Tool.of(
                name = "echo",
                description = "Echo",
            ) { input ->
                Tool.Result.text("Received: $input")
            }

            val callback = SpringToolCallbackAdapter(tool)
            val result = callback.call("""{"message": "hello"}""")

            assertEquals("Received: {\"message\": \"hello\"}", result)
        }

        @Test
        fun `adapter handles error result`() {
            val tool = Tool.of(
                name = "failing",
                description = "Fails",
            ) { _ ->
                Tool.Result.error("Something went wrong")
            }

            val callback = SpringToolCallbackAdapter(tool)
            val result = callback.call("{}")

            assertTrue(result.startsWith("ERROR:"))
            assertTrue(result.contains("Something went wrong"))
        }

        @Test
        fun `adapter handles artifact result returning content`() {
            val tool = Tool.of(
                name = "artifact_tool",
                description = "Returns artifact",
            ) { _ ->
                Tool.Result.withArtifact("Generated content", byteArrayOf(1, 2, 3))
            }

            val callback = SpringToolCallbackAdapter(tool)
            val result = callback.call("{}")

            assertEquals("Generated content", result)
        }

        @Test
        fun `adapter handles exception during execution`() {
            val tool = Tool.of(
                name = "throwing",
                description = "Throws",
            ) { _ ->
                throw RuntimeException("Unexpected error")
            }

            val callback = SpringToolCallbackAdapter(tool)
            val result = callback.call("{}")

            assertTrue(result.startsWith("ERROR:"))
            assertTrue(result.contains("Unexpected error"))
        }

        @Test
        fun `extension function toSpringToolCallback works`() {
            val tool = Tool.of(
                name = "ext_test",
                description = "Extension test",
            ) { _ ->
                Tool.Result.text("ok")
            }

            val callback = tool.toSpringToolCallback()

            assertTrue(callback is SpringToolCallbackAdapter)
            assertEquals("ext_test", callback.toolDefinition.name())
        }

        @Test
        fun `extension function toSpringToolCallbacks works for list`() {
            val tools = listOf(
                Tool.of("tool1", "First") { _ -> Tool.Result.text("1") },
                Tool.of("tool2", "Second") { _ -> Tool.Result.text("2") },
            )

            val callbacks = tools.toSpringToolCallbacks()

            assertEquals(2, callbacks.size)
            assertEquals("tool1", callbacks[0].toolDefinition.name())
            assertEquals("tool2", callbacks[1].toolDefinition.name())
        }
    }

    @Nested
    inner class SpringToToolWrapper {

        @Test
        fun `wrapper converts Spring callback definition`() {
            val springCallback = createMockSpringCallback(
                name = "spring_tool",
                description = "A Spring tool",
                inputSchema = """{"type": "object"}""",
            )

            val tool = SpringToolCallbackWrapper(springCallback)

            assertEquals("spring_tool", tool.definition.name)
            assertEquals("A Spring tool", tool.definition.description)
            assertEquals("""{"type": "object"}""", tool.definition.inputSchema.toJsonSchema())
        }

        @Test
        fun `wrapper converts Spring callback metadata`() {
            val springCallback = createMockSpringCallback(
                name = "test",
                returnDirect = true,
            )

            val tool = SpringToolCallbackWrapper(springCallback)

            assertTrue(tool.metadata.returnDirect)
        }

        @Test
        fun `wrapper executes Spring callback`() {
            val springCallback = createMockSpringCallback(
                name = "test",
                callResult = "Spring result",
            )

            val tool = SpringToolCallbackWrapper(springCallback)
            val result = tool.call("{}")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Spring result", (result as Tool.Result.Text).content)
        }

        @Test
        fun `wrapper handles Spring callback exception`() {
            val springCallback = createMockSpringCallback(
                name = "test",
                throwOnCall = RuntimeException("Spring error"),
            )

            val tool = SpringToolCallbackWrapper(springCallback)
            val result = tool.call("{}")

            assertTrue(result is Tool.Result.Error)
            assertEquals("Spring error", (result as Tool.Result.Error).message)
        }

        @Test
        fun `extension function toEmbabelTool works`() {
            val springCallback = createMockSpringCallback(name = "ext_spring")

            val tool = springCallback.toEmbabelTool()

            assertTrue(tool is SpringToolCallbackWrapper)
            assertEquals("ext_spring", tool.definition.name)
        }

        @Test
        fun `extension function toEmbabelTools works for list`() {
            val callbacks = listOf(
                createMockSpringCallback(name = "spring1"),
                createMockSpringCallback(name = "spring2"),
            )

            val tools = callbacks.toEmbabelTools()

            assertEquals(2, tools.size)
            assertEquals("spring1", tools[0].definition.name)
            assertEquals("spring2", tools[1].definition.name)
        }

        private fun createMockSpringCallback(
            name: String,
            description: String = "",
            inputSchema: String = "{}",
            returnDirect: Boolean = false,
            callResult: String = "result",
            throwOnCall: Exception? = null,
        ): ToolCallback {
            return object : ToolCallback {
                override fun getToolDefinition() = DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build()

                override fun getToolMetadata() = DefaultToolMetadata.builder()
                    .returnDirect(returnDirect)
                    .build()

                override fun call(toolInput: String): String {
                    if (throwOnCall != null) throw throwOnCall
                    return callResult
                }
            }
        }
    }

    @Nested
    inner class RoundTrip {

        @Test
        fun `tool survives round trip through Spring adapter`() {
            val originalTool = Tool.of(
                name = "roundtrip",
                description = "Round trip test",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("value", Tool.ParameterType.INTEGER, "A value"),
                ),
            ) { input ->
                Tool.Result.text("Processed: $input")
            }

            // Convert to Spring and back
            val springCallback = originalTool.toSpringToolCallback()
            val wrappedTool = springCallback.toEmbabelTool()

            assertEquals(originalTool.definition.name, wrappedTool.definition.name)
            assertEquals(originalTool.definition.description, wrappedTool.definition.description)

            // Execute through wrapped tool
            val result = wrappedTool.call("""{"value": 42}""")
            assertTrue(result is Tool.Result.Text)
            assertTrue((result as Tool.Result.Text).content.contains("42"))
        }
    }

    /**
     * Tests that verify the JSON schema produced by SpringToolCallbackAdapter is always
     * valid JSON that can be parsed by Spring AI's ModelOptionsUtils.jsonToMap().
     * This catches issues like unescaped special characters in descriptions.
     */
    @Nested
    inner class JsonSchemaValidity {

        private val objectMapper = ObjectMapper()

        private fun assertValidJsonSchema(tool: Tool) {
            val callback = SpringToolCallbackAdapter(tool)
            val schema = callback.toolDefinition.inputSchema()

            // Verify it's valid JSON that Jackson can parse
            assertDoesNotThrow {
                objectMapper.readTree(schema)
            }

            // Verify Spring AI's ModelOptionsUtils can parse it (this is what fails in production)
            assertDoesNotThrow {
                ModelOptionsUtils.jsonToMap(schema)
            }
        }

        @Test
        fun `schema with simple description produces valid JSON`() {
            val tool = Tool.of(
                name = "simple_tool",
                description = "A simple tool",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("input", Tool.ParameterType.STRING, "Simple input"),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `schema with quotes in description produces valid JSON`() {
            val tool = Tool.of(
                name = "quote_tool",
                description = "A tool with \"quotes\"",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("query", Tool.ParameterType.STRING, "The \"search\" query to use"),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `schema with backslash in description produces valid JSON`() {
            val tool = Tool.of(
                name = "backslash_tool",
                description = "A tool with backslash",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("path", Tool.ParameterType.STRING, "Windows path like C:\\Users\\test"),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `schema with newlines in description produces valid JSON`() {
            val tool = Tool.of(
                name = "newline_tool",
                description = "A tool",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("text", Tool.ParameterType.STRING, "Text with\nnewlines\tin it"),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `schema with unicode characters produces valid JSON`() {
            val tool = Tool.of(
                name = "unicode_tool",
                description = "A tool",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("emoji", Tool.ParameterType.STRING, "Emoji: 😀 and unicode: ñ é ü"),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `schema with mixed special characters produces valid JSON`() {
            val tool = Tool.of(
                name = "mixed_tool",
                description = "Tool \"test\"",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter(
                        "complex",
                        Tool.ParameterType.STRING,
                        """Query with "quotes", backslash \, tabs	and newlines
and special chars: <>&""",
                    ),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `schema with multiple parameters with special chars produces valid JSON`() {
            val tool = Tool.of(
                name = "multi_param_tool",
                description = "A tool",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("first", Tool.ParameterType.STRING, "First \"param\""),
                    Tool.Parameter("second", Tool.ParameterType.STRING, "Second\\param"),
                    Tool.Parameter("third", Tool.ParameterType.INTEGER, "Third\nparam"),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `schema with enum values containing special chars produces valid JSON`() {
            val tool = Tool.of(
                name = "enum_tool",
                description = "A tool",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter(
                        name = "option",
                        type = Tool.ParameterType.STRING,
                        description = "Choose an option",
                        enumValues = listOf("value\"one", "value\\two", "value\nthree"),
                    ),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `empty schema produces valid JSON`() {
            val tool = Tool.of(
                name = "empty_tool",
                description = "No parameters",
                inputSchema = Tool.InputSchema.empty(),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }

        @Test
        fun `parameter name with special characters produces valid JSON`() {
            val tool = Tool.of(
                name = "special_name_tool",
                description = "A tool",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("param\"name", Tool.ParameterType.STRING, "A parameter"),
                ),
            ) { _ -> Tool.Result.text("ok") }

            assertValidJsonSchema(tool)
        }
    }
}
