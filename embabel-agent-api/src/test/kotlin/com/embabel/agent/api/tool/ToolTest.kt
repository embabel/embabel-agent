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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.LlmTool.Param
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolTest {

    // Test fixtures for annotation-based tools
    class WeatherTools {
        @LlmTool(description = "Get current weather for a city")
        fun getWeather(
            @Param(description = "City name") city: String,
            @Param(description = "Temperature units", required = false) units: String = "celsius",
        ): String {
            return "Weather in $city: sunny, 22 $units"
        }

        @LlmTool(name = "calculate_sum", description = "Add two numbers")
        fun addNumbers(
            @Param(description = "First number") a: Int,
            @Param(description = "Second number") b: Int,
        ): Int {
            return a + b
        }

        @LlmTool(description = "Tool that returns directly", returnDirect = true)
        fun directReturn(): String {
            return "Direct result"
        }
    }

    class NoToolMethods {
        fun regularMethod(): String = "not a tool"
    }

    class MixedMethods {
        @LlmTool(description = "A tool method")
        fun toolMethod(): String = "tool"

        fun regularMethod(): String = "not a tool"
    }

    data class Person(
        val name: String,
        val age: Int,
    )

    class ComplexTools {
        @LlmTool(description = "Create a person")
        fun createPerson(
            @Param(description = "Person's name") name: String,
            @Param(description = "Person's age") age: Int,
        ): Person {
            return Person(name, age)
        }

        @LlmTool(description = "Tool that can return custom result")
        fun customResult(@Param(description = "Input") input: String): Tool.Result {
            return Tool.Result.withArtifact("Processed: $input", mapOf("data" to input))
        }
    }

    @Nested
    inner class ToolCreation {

        @Test
        fun `create tool with parameters using Tool of`() {
            val tool = Tool.of(
                name = "get_weather",
                description = "Get current weather for a city",
                inputSchema = Tool.InputSchema.of(
                    Tool.Parameter("city", Tool.ParameterType.STRING, "City name"),
                    Tool.Parameter("units", Tool.ParameterType.STRING, "celsius or fahrenheit", required = false),
                ),
            ) { input ->
                Tool.Result.text("Sunny, 22C")
            }

            assertEquals("get_weather", tool.definition.name)
            assertEquals("Get current weather for a city", tool.definition.description)
            assertEquals(2, tool.definition.inputSchema.parameters.size)
        }

        @Test
        fun `create tool with no parameters`() {
            val tool = Tool.of(
                name = "get_time",
                description = "Get current time",
            ) { _ ->
                Tool.Result.text("12:00 PM")
            }

            assertEquals("get_time", tool.definition.name)
            assertTrue(tool.definition.inputSchema.parameters.isEmpty())
        }

        @Test
        fun `create tool with custom metadata`() {
            val tool = Tool.of(
                name = "calculator",
                description = "Perform calculations",
                metadata = Tool.Metadata(returnDirect = true),
            ) { _ ->
                Tool.Result.text("42")
            }

            assertTrue(tool.metadata.returnDirect)
        }
    }

    @Nested
    inner class ToolExecution {

        @Test
        fun `tool returns text result`() {
            val tool = Tool.of(
                name = "echo",
                description = "Echo input",
            ) { input ->
                Tool.Result.text("Echo: $input")
            }

            val result = tool.call("""{"message": "hello"}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Echo: {\"message\": \"hello\"}", (result as Tool.Result.Text).content)
        }

        @Test
        fun `tool returns error result`() {
            val tool = Tool.of(
                name = "failing_tool",
                description = "Always fails",
            ) { _ ->
                Tool.Result.error("Something went wrong")
            }

            val result = tool.call("{}")

            assertTrue(result is Tool.Result.Error)
            assertEquals("Something went wrong", (result as Tool.Result.Error).message)
        }

        @Test
        fun `tool returns artifact result`() {
            val tool = Tool.of(
                name = "generate_image",
                description = "Generate an image",
            ) { _ ->
                Tool.Result.withArtifact("Image generated", byteArrayOf(1, 2, 3))
            }

            val result = tool.call("{}")

            assertTrue(result is Tool.Result.WithArtifact)
            val artifactResult = result as Tool.Result.WithArtifact
            assertEquals("Image generated", artifactResult.content)
            assertTrue(artifactResult.artifact is ByteArray)
        }

    }

    @Nested
    inner class InputSchemaGeneration {

        @Test
        fun `empty schema generates valid JSON`() {
            val schema = Tool.InputSchema.empty()

            val json = schema.toJsonSchema()

            assertEquals("""{"type": "object", "properties": {}}""", json)
        }

        @Test
        fun `schema with string parameter`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter("name", Tool.ParameterType.STRING, "User name"),
            )

            val json = schema.toJsonSchema()

            assertTrue(json.contains("\"name\""))
            assertTrue(json.contains("\"type\": \"string\""))
            assertTrue(json.contains("\"description\": \"User name\""))
            assertTrue(json.contains("\"required\": [\"name\"]"))
        }

        @Test
        fun `schema with optional parameter`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter("name", Tool.ParameterType.STRING, "User name", required = false),
            )

            val json = schema.toJsonSchema()

            assertTrue(json.contains("\"name\""))
            assertTrue(json.contains("\"required\": []"))
        }

        @Test
        fun `schema with enum parameter`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter(
                    name = "color",
                    type = Tool.ParameterType.STRING,
                    description = "Color choice",
                    enumValues = listOf("red", "green", "blue"),
                ),
            )

            val json = schema.toJsonSchema()

            assertTrue(json.contains("\"enum\": [\"red\", \"green\", \"blue\"]"))
        }

        @Test
        fun `schema with multiple parameter types`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter("name", Tool.ParameterType.STRING, "Name"),
                Tool.Parameter("age", Tool.ParameterType.INTEGER, "Age"),
                Tool.Parameter("score", Tool.ParameterType.NUMBER, "Score"),
                Tool.Parameter("active", Tool.ParameterType.BOOLEAN, "Is active"),
            )

            val json = schema.toJsonSchema()

            assertTrue(json.contains("\"type\": \"string\""))
            assertTrue(json.contains("\"type\": \"integer\""))
            assertTrue(json.contains("\"type\": \"number\""))
            assertTrue(json.contains("\"type\": \"boolean\""))
        }
    }

    @Nested
    inner class DefinitionCreation {

        @Test
        fun `create definition with companion invoke`() {
            val schema = Tool.InputSchema.empty()
            val definition = Tool.Definition("test", "Test tool", schema)

            assertEquals("test", definition.name)
            assertEquals("Test tool", definition.description)
            assertSame(schema, definition.inputSchema)
        }
    }

    @Nested
    inner class MetadataCreation {

        @Test
        fun `default metadata has expected values`() {
            val metadata = Tool.Metadata.DEFAULT

            assertFalse(metadata.returnDirect)
            assertTrue(metadata.providerMetadata.isEmpty())
        }

        @Test
        fun `create metadata with companion invoke`() {
            val metadata = Tool.Metadata(
                returnDirect = true,
                providerMetadata = mapOf("key" to "value"),
            )

            assertTrue(metadata.returnDirect)
            assertEquals("value", metadata.providerMetadata["key"])
        }
    }

    @Nested
    inner class ResultCreation {

        @Test
        fun `create text result with companion`() {
            val result = Tool.Result.text("hello")

            assertTrue(result is Tool.Result.Text)
            assertEquals("hello", (result as Tool.Result.Text).content)
        }

        @Test
        fun `create error result with companion`() {
            val cause = RuntimeException("cause")
            val result = Tool.Result.error("failed", cause)

            assertTrue(result is Tool.Result.Error)
            val error = result as Tool.Result.Error
            assertEquals("failed", error.message)
            assertSame(cause, error.cause)
        }

        @Test
        fun `create artifact result with companion`() {
            val artifact = mapOf("data" to "value")
            val result = Tool.Result.withArtifact("success", artifact)

            assertTrue(result is Tool.Result.WithArtifact)
            val artifactResult = result as Tool.Result.WithArtifact
            assertEquals("success", artifactResult.content)
            assertSame(artifact, artifactResult.artifact)
        }
    }

    @Nested
    inner class FromInstance {

        @Test
        fun `creates tools from all annotated methods`() {
            val tools = Tool.fromInstance(WeatherTools())

            assertEquals(3, tools.size)
            val toolNames = tools.map { it.definition.name }.toSet()
            assertTrue(toolNames.contains("getWeather"))
            assertTrue(toolNames.contains("calculate_sum"))
            assertTrue(toolNames.contains("directReturn"))
        }

        @Test
        fun `throws when no annotated methods found`() {
            val exception = assertThrows<IllegalArgumentException> {
                Tool.fromInstance(NoToolMethods())
            }
            assertTrue(exception.message!!.contains("No methods annotated"))
        }

        @Test
        fun `only includes annotated methods`() {
            val tools = Tool.fromInstance(MixedMethods())

            assertEquals(1, tools.size)
            assertEquals("toolMethod", tools[0].definition.name)
        }
    }

    @Nested
    inner class SafelyFromInstance {

        @Test
        fun `returns tools when found`() {
            val tools = Tool.safelyFromInstance(WeatherTools())

            assertEquals(3, tools.size)
        }

        @Test
        fun `returns empty list when no annotated methods`() {
            val tools = Tool.safelyFromInstance(NoToolMethods())

            assertTrue(tools.isEmpty())
        }
    }

    @Nested
    inner class MethodToolDefinition {

        @Test
        fun `uses method name when annotation name is empty`() {
            val tools = Tool.fromInstance(WeatherTools())
            val weatherTool = tools.find { it.definition.name == "getWeather" }

            assertNotNull(weatherTool)
            assertEquals("getWeather", weatherTool!!.definition.name)
        }

        @Test
        fun `uses annotation name when provided`() {
            val tools = Tool.fromInstance(WeatherTools())
            val sumTool = tools.find { it.definition.name == "calculate_sum" }

            assertNotNull(sumTool)
            assertEquals("calculate_sum", sumTool!!.definition.name)
        }

        @Test
        fun `captures description from annotation`() {
            val tools = Tool.fromInstance(WeatherTools())
            val weatherTool = tools.find { it.definition.name == "getWeather" }

            assertEquals("Get current weather for a city", weatherTool!!.definition.description)
        }

        @Test
        fun `captures returnDirect metadata`() {
            val tools = Tool.fromInstance(WeatherTools())
            val directTool = tools.find { it.definition.name == "directReturn" }
            val normalTool = tools.find { it.definition.name == "getWeather" }

            assertTrue(directTool!!.metadata.returnDirect)
            assertFalse(normalTool!!.metadata.returnDirect)
        }

        @Test
        fun `generates correct input schema from parameters`() {
            val tools = Tool.fromInstance(WeatherTools())
            val weatherTool = tools.find { it.definition.name == "getWeather" }!!

            val params = weatherTool.definition.inputSchema.parameters
            assertEquals(2, params.size)

            val cityParam = params.find { it.name == "city" }!!
            assertEquals(Tool.ParameterType.STRING, cityParam.type)
            assertEquals("City name", cityParam.description)
            assertTrue(cityParam.required)

            val unitsParam = params.find { it.name == "units" }!!
            assertEquals(Tool.ParameterType.STRING, unitsParam.type)
            assertEquals("Temperature units", unitsParam.description)
            assertFalse(unitsParam.required)
        }

        @Test
        fun `maps integer parameters correctly`() {
            val tools = Tool.fromInstance(WeatherTools())
            val sumTool = tools.find { it.definition.name == "calculate_sum" }!!

            val params = sumTool.definition.inputSchema.parameters
            assertEquals(2, params.size)
            assertTrue(params.all { it.type == Tool.ParameterType.INTEGER })
        }
    }

    @Nested
    inner class MethodToolExecution {

        @Test
        fun `executes method with string parameters`() {
            val tools = Tool.fromInstance(WeatherTools())
            val weatherTool = tools.find { it.definition.name == "getWeather" }!!

            val result = weatherTool.call("""{"city": "London", "units": "fahrenheit"}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Weather in London: sunny, 22 fahrenheit", (result as Tool.Result.Text).content)
        }

        @Test
        fun `executes method with default parameter values`() {
            val tools = Tool.fromInstance(WeatherTools())
            val weatherTool = tools.find { it.definition.name == "getWeather" }!!

            val result = weatherTool.call("""{"city": "Paris"}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Weather in Paris: sunny, 22 celsius", (result as Tool.Result.Text).content)
        }

        @Test
        fun `executes method with integer parameters`() {
            val tools = Tool.fromInstance(WeatherTools())
            val sumTool = tools.find { it.definition.name == "calculate_sum" }!!

            val result = sumTool.call("""{"a": 5, "b": 3}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals("8", (result as Tool.Result.Text).content)
        }

        @Test
        fun `executes method returning complex object`() {
            val tools = Tool.fromInstance(ComplexTools())
            val createTool = tools.find { it.definition.name == "createPerson" }!!

            val result = createTool.call("""{"name": "Alice", "age": 30}""")

            assertTrue(result is Tool.Result.Text)
            val content = (result as Tool.Result.Text).content
            assertTrue(content.contains("Alice"))
            assertTrue(content.contains("30"))
        }

        @Test
        fun `preserves Tool Result when method returns it`() {
            val tools = Tool.fromInstance(ComplexTools())
            val customTool = tools.find { it.definition.name == "customResult" }!!

            val result = customTool.call("""{"input": "test"}""")

            assertTrue(result is Tool.Result.WithArtifact)
            val artifactResult = result as Tool.Result.WithArtifact
            assertEquals("Processed: test", artifactResult.content)
        }

        @Test
        fun `handles execution errors gracefully`() {
            class FailingTools {
                @LlmTool(description = "Always fails")
                fun fail(): String {
                    throw RuntimeException("Intentional failure")
                }
            }

            val tools = Tool.fromInstance(FailingTools())
            val result = tools[0].call("{}")

            assertTrue(result is Tool.Result.Error)
            assertTrue((result as Tool.Result.Error).message.contains("Intentional failure"))
        }

        @Test
        fun `handles empty input`() {
            val tools = Tool.fromInstance(WeatherTools())
            val directTool = tools.find { it.definition.name == "directReturn" }!!

            val result = directTool.call("")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Direct result", (result as Tool.Result.Text).content)
        }
    }

    @Nested
    inner class AgenticToolCreation {

        private val echoTool = Tool.of("echo", "Echo input") { input ->
            Tool.Result.text("Echo: $input")
        }

        private val reverseTool = Tool.of("reverse", "Reverse input") { input ->
            Tool.Result.text(input.reversed())
        }

        @Test
        fun `create agentic tool with factory method`() {
            val agentic = Tool.Agentic.create(
                name = "orchestrator",
                description = "Orchestrates tools",
                tools = listOf(echoTool, reverseTool),
                prompt = "Process the input using available tools",
            )

            assertEquals("orchestrator", agentic.definition.name)
            assertEquals("Orchestrates tools", agentic.definition.description)
            assertEquals(2, agentic.tools.size)
            assertEquals("Process the input using available tools", agentic.prompt)
            assertNull(agentic.model)
            assertNull(agentic.role)
        }

        @Test
        fun `create agentic tool with vararg tools`() {
            val agentic = Tool.Agentic.create(
                name = "multi",
                description = "Multi-tool",
                prompt = "Use tools",
                echoTool, reverseTool,
            )

            assertEquals(2, agentic.tools.size)
        }

        @Test
        fun `create agentic tool with model`() {
            val agentic = Tool.Agentic.withModel(
                name = "modeled",
                description = "With model",
                model = "gpt-4",
                tools = listOf(echoTool),
                prompt = "Do stuff",
            )

            assertEquals("gpt-4", agentic.model)
            assertNull(agentic.role)
        }

        @Test
        fun `create agentic tool with role`() {
            val agentic = Tool.Agentic.withRole(
                name = "roled",
                description = "With role",
                role = "researcher",
                tools = listOf(echoTool),
                prompt = "Research stuff",
            )

            assertEquals("researcher", agentic.role)
            assertNull(agentic.model)
        }

        @Test
        fun `create agentic tool with both model and role`() {
            val agentic = Tool.Agentic.create(
                name = "both",
                description = "With both",
                model = "claude-sonnet",
                role = "backup-role",
                tools = listOf(echoTool),
                prompt = "Do things",
            )

            assertEquals("claude-sonnet", agentic.model)
            assertEquals("backup-role", agentic.role)
        }

        @Test
        fun `create agentic tool with input schema`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter("topic", Tool.ParameterType.STRING, "Topic to research"),
            )

            val agentic = Tool.Agentic.create(
                name = "researcher",
                description = "Research a topic",
                tools = listOf(echoTool),
                prompt = "Research {topic}",
                inputSchema = schema,
            )

            assertEquals(1, agentic.definition.inputSchema.parameters.size)
            assertEquals("topic", agentic.definition.inputSchema.parameters[0].name)
        }

        @Test
        fun `create agentic tool with custom metadata`() {
            val agentic = Tool.Agentic.create(
                name = "direct",
                description = "Returns directly",
                tools = listOf(echoTool),
                prompt = "Process",
                metadata = Tool.Metadata(returnDirect = true),
            )

            assertTrue(agentic.metadata.returnDirect)
        }

        @Test
        fun `agentic tool requires at least one tool`() {
            val exception = assertThrows<IllegalArgumentException> {
                Tool.Agentic.create(
                    name = "empty",
                    description = "No tools",
                    tools = emptyList(),
                    prompt = "Nothing",
                )
            }
            assertTrue(exception.message!!.contains("at least one sub-tool"))
        }

        @Test
        fun `agentic tool requires non-blank prompt`() {
            val exception = assertThrows<IllegalArgumentException> {
                Tool.Agentic.create(
                    name = "blank",
                    description = "Blank prompt",
                    tools = listOf(echoTool),
                    prompt = "   ",
                )
            }
            assertTrue(exception.message!!.contains("prompt must not be blank"))
        }
    }

    @Nested
    inner class AgenticToolWithers {

        private val baseTool = Tool.of("base", "Base tool") { Tool.Result.text("base") }
        private val extraTool = Tool.of("extra", "Extra tool") { Tool.Result.text("extra") }

        private val agentic = Tool.Agentic.create(
            name = "test",
            description = "Test agentic",
            tools = listOf(baseTool),
            prompt = "Original prompt",
        )

        @Test
        fun `withModel creates copy with new model`() {
            val updated = agentic.withModel("gpt-4")

            assertEquals("gpt-4", updated.model)
            assertEquals(agentic.definition, updated.definition)
            assertEquals(agentic.prompt, updated.prompt)
            assertEquals(agentic.tools, updated.tools)
        }

        @Test
        fun `withRole creates copy with new role`() {
            val updated = agentic.withRole("researcher")

            assertEquals("researcher", updated.role)
            assertEquals(agentic.definition, updated.definition)
        }

        @Test
        fun `withTools adds additional tools`() {
            val updated = agentic.withTools(extraTool)

            assertEquals(2, updated.tools.size)
            assertTrue(updated.tools.contains(baseTool))
            assertTrue(updated.tools.contains(extraTool))
        }

        @Test
        fun `withPrompt creates copy with new prompt`() {
            val updated = agentic.withPrompt("New prompt")

            assertEquals("New prompt", updated.prompt)
            assertEquals(agentic.definition, updated.definition)
        }
    }

    @Nested
    inner class AgenticToolImplementsTool {

        private val subTool = Tool.of("sub", "Sub tool") { Tool.Result.text("sub") }

        @Test
        fun `agentic tool implements Tool interface`() {
            val agentic = Tool.Agentic.create(
                name = "impl",
                description = "Implements Tool",
                tools = listOf(subTool),
                prompt = "Do it",
            )

            assertTrue(agentic is Tool)
            assertEquals("impl", agentic.definition.name)
            assertEquals("Implements Tool", agentic.definition.description)
        }

        @Test
        fun `call returns error result when no AgentProcess context`() {
            val agentic = Tool.Agentic.create(
                name = "nocontext",
                description = "No context",
                tools = listOf(subTool),
                prompt = "Try it",
            )

            val result = agentic.call("{}")

            assertTrue(result is Tool.Result.Error)
            val error = result as Tool.Result.Error
            assertTrue(error.message.contains("No AgentProcess context"))
        }
    }
}
