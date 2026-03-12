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

    /**
     * Tools with List parameters - like MathTools.mean(numbers: List<Double>)
     */
    class ListParameterTools {
        @LlmTool(description = "Find the mean of a list of numbers")
        fun mean(@Param(description = "List of numbers to average") numbers: List<Double>): Double {
            return if (numbers.isEmpty()) 0.0 else numbers.sum() / numbers.size
        }

        @LlmTool(description = "Find the minimum value in a list of numbers")
        fun min(@Param(description = "List of numbers") numbers: List<Double>): Double {
            return numbers.minOrNull() ?: Double.NaN
        }

        @LlmTool(description = "Sum a list of integers")
        fun sumIntegers(@Param(description = "List of integers") numbers: List<Int>): Int {
            return numbers.sum()
        }

        @LlmTool(description = "Concatenate a list of strings")
        fun concatenate(@Param(description = "List of strings") strings: List<String>): String {
            return strings.joinToString("")
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

            assertEquals("""{"type":"object","properties":{}}""", json)
        }

        @Test
        fun `schema with string parameter`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter("name", Tool.ParameterType.STRING, "User name"),
            )

            val json = schema.toJsonSchema()

            assertTrue(json.contains("\"name\""))
            assertTrue(json.contains("\"type\":\"string\""))
            assertTrue(json.contains("\"description\":\"User name\""))
            assertTrue(json.contains("\"required\":[\"name\"]"))
        }

        @Test
        fun `schema with optional parameter`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter("name", Tool.ParameterType.STRING, "User name", required = false),
            )

            val json = schema.toJsonSchema()

            assertTrue(json.contains("\"name\""))
            // When there's no required params, the key is omitted
            assertFalse(json.contains("\"required\""))
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

            assertTrue(json.contains("\"enum\":[\"red\",\"green\",\"blue\"]"))
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

            assertTrue(json.contains("\"type\":\"string\""))
            assertTrue(json.contains("\"type\":\"integer\""))
            assertTrue(json.contains("\"type\":\"number\""))
            assertTrue(json.contains("\"type\":\"boolean\""))
        }

        @Test
        fun `schema for method with List parameter should include items type`() {
            // This tests that MathTools.mean(numbers: List<Double>) generates correct schema
            val tools = Tool.fromInstance(ListParameterTools())
            val meanTool = tools.find { it.definition.name == "mean" }!!

            val json = meanTool.definition.inputSchema.toJsonSchema()

            // Should have array type for the numbers parameter
            assertTrue(json.contains("\"type\":\"array\""), "Schema should have array type: $json")

            // IMPORTANT: Should have items property specifying element type
            // Without this, LLMs don't know what type of elements the array should contain
            assertTrue(json.contains("\"items\""), "Schema should have items property for array: $json")
            assertTrue(
                json.contains("\"items\":{\"type\":\"number\"}") ||
                        json.contains("\"items\": {\"type\": \"number\"}") ||
                        json.contains("\"items\":{\"type\":\"integer\"}") ||
                        json.contains("\"items\": {\"type\": \"integer\"}"),
                "Array items should have numeric type: $json"
            )
        }

        @Test
        fun `schema for method with List of String parameter should include string items type`() {
            val tools = Tool.fromInstance(ListParameterTools())
            val concatenateTool = tools.find { it.definition.name == "concatenate" }!!

            val json = concatenateTool.definition.inputSchema.toJsonSchema()

            // Should have array type
            assertTrue(json.contains("\"type\":\"array\""), "Schema should have array type: $json")

            // Should have items property with string type
            assertTrue(json.contains("\"items\""), "Schema should have items property for array: $json")
            assertTrue(
                json.contains("\"items\":{\"type\":\"string\"}") ||
                        json.contains("\"items\": {\"type\": \"string\"}"),
                "Array items should have string type: $json"
            )
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

    // Test fixtures for complex type schema generation (Issue #1326)
    data class WrappedType(
        val internalId: Int,
        val name: String,
    )

    data class Address(
        val street: String,
        val city: String,
        val zipCode: String,
    )

    data class NestedWrapper(
        val address: Address,
        val label: String,
    )

    class ComplexParameterTools {
        @LlmTool(description = "Get information using a wrapped type")
        fun getInformation(
            @Param(description = "The wrapped type") someType: WrappedType,
        ): String {
            return "ID: ${someType.internalId}, Name: ${someType.name}"
        }

        @LlmTool(description = "Process an address")
        fun processAddress(
            @Param(description = "The address to process") address: Address,
        ): String {
            return "${address.street}, ${address.city} ${address.zipCode}"
        }

        @LlmTool(description = "Handle nested complex types")
        fun handleNested(
            @Param(description = "Nested wrapper") wrapper: NestedWrapper,
        ): String {
            return "${wrapper.label}: ${wrapper.address.city}"
        }

        @LlmTool(description = "Mix of simple and complex params")
        fun mixedParams(
            @Param(description = "Simple string") name: String,
            @Param(description = "Complex type") wrapped: WrappedType,
            @Param(description = "Simple int") count: Int,
        ): String {
            return "$name: ${wrapped.name} x $count"
        }
    }

    @Nested
    inner class ComplexTypeSchemaGeneration {

        @Test
        fun `schema for complex type parameter should include nested properties`() {
            val tools = Tool.fromInstance(ComplexParameterTools())
            val tool = tools.find { it.definition.name == "getInformation" }!!

            val json = tool.definition.inputSchema.toJsonSchema()

            // The schema should include the properties of WrappedType
            assertTrue(json.contains("\"internalId\""), "Schema should contain internalId property: $json")
            assertTrue(json.contains("\"name\""), "Schema should contain name property: $json")
            assertTrue(json.contains("\"integer\""), "internalId should have integer type: $json")
        }

        @Test
        fun `schema for address parameter should include all address properties`() {
            val tools = Tool.fromInstance(ComplexParameterTools())
            val tool = tools.find { it.definition.name == "processAddress" }!!

            val json = tool.definition.inputSchema.toJsonSchema()

            assertTrue(json.contains("\"street\""), "Schema should contain street property: $json")
            assertTrue(json.contains("\"city\""), "Schema should contain city property: $json")
            assertTrue(json.contains("\"zipCode\""), "Schema should contain zipCode property: $json")
        }

        @Test
        fun `schema for nested complex types should include all levels`() {
            val tools = Tool.fromInstance(ComplexParameterTools())
            val tool = tools.find { it.definition.name == "handleNested" }!!

            val json = tool.definition.inputSchema.toJsonSchema()

            // Should have the wrapper's direct properties
            assertTrue(json.contains("\"label\""), "Schema should contain label property: $json")
            assertTrue(json.contains("\"address\""), "Schema should contain address property: $json")
            // Should have nested address properties
            assertTrue(json.contains("\"street\""), "Schema should contain nested street property: $json")
            assertTrue(json.contains("\"city\""), "Schema should contain nested city property: $json")
        }

        @Test
        fun `schema with mixed simple and complex params should handle both correctly`() {
            val tools = Tool.fromInstance(ComplexParameterTools())
            val tool = tools.find { it.definition.name == "mixedParams" }!!

            val json = tool.definition.inputSchema.toJsonSchema()

            // Simple params should be present
            assertTrue(json.contains("\"name\""), "Schema should contain name param: $json")
            assertTrue(json.contains("\"count\""), "Schema should contain count param: $json")
            // Complex type's properties should also be present
            assertTrue(json.contains("\"internalId\""), "Schema should contain wrapped type's internalId: $json")
        }

        @Test
        fun `execution with complex type parameter should work correctly`() {
            val tools = Tool.fromInstance(ComplexParameterTools())
            val tool = tools.find { it.definition.name == "getInformation" }!!

            val result = tool.call("""{"someType": {"internalId": 42, "name": "Test"}}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals("ID: 42, Name: Test", (result as Tool.Result.Text).content)
        }

        @Test
        fun `execution with nested complex type should work correctly`() {
            val tools = Tool.fromInstance(ComplexParameterTools())
            val tool = tools.find { it.definition.name == "handleNested" }!!

            val result =
                tool.call("""{"wrapper": {"address": {"street": "123 Main", "city": "Boston", "zipCode": "02101"}, "label": "Home"}}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Home: Boston", (result as Tool.Result.Text).content)
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

            assertTrue(result is Tool.Result.WithArtifact)
            assertEquals("8", (result as Tool.Result.WithArtifact).content)
        }

        @Test
        fun `executes method returning complex object produces WithArtifact`() {
            val tools = Tool.fromInstance(ComplexTools())
            val createTool = tools.find { it.definition.name == "createPerson" }!!

            val result = createTool.call("""{"name": "Alice", "age": 30}""")

            assertTrue(result is Tool.Result.WithArtifact)
            val artifactResult = result as Tool.Result.WithArtifact
            assertTrue(artifactResult.content.contains("Alice"))
            assertTrue(artifactResult.content.contains("30"))
            val person = artifactResult.artifact as Person
            assertEquals("Alice", person.name)
            assertEquals(30, person.age)
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
    inner class WithDescriptionTest {

        @Test
        fun `withDescription creates tool with new description`() {
            val original = Tool.of(
                name = "test",
                description = "Original description",
            ) { Tool.Result.text("result") }

            val modified = original.withDescription("New description")

            assertEquals("New description", modified.definition.description)
            assertEquals("test", modified.definition.name)
        }

        @Test
        fun `withDescription preserves tool functionality`() {
            val original = Tool.of(
                name = "test",
                description = "Original description",
            ) { Tool.Result.text("expected result") }

            val modified = original.withDescription("New description")
            val result = modified.call("{}")

            assertTrue(result is Tool.Result.Text)
            assertEquals("expected result", (result as Tool.Result.Text).content)
        }

        @Test
        fun `withDescription preserves input schema`() {
            val schema = Tool.InputSchema.of(
                Tool.Parameter("param1", Tool.ParameterType.STRING, "A parameter"),
            )
            val original = Tool.of(
                name = "test",
                description = "Original description",
                inputSchema = schema,
            ) { Tool.Result.text("result") }

            val modified = original.withDescription("New description")

            assertEquals(1, modified.definition.inputSchema.parameters.size)
            assertEquals("param1", modified.definition.inputSchema.parameters[0].name)
        }

        @Test
        fun `withDescription preserves metadata`() {
            val original = Tool.of(
                name = "test",
                description = "Original description",
                metadata = Tool.Metadata(returnDirect = true),
            ) { Tool.Result.text("result") }

            val modified = original.withDescription("New description")

            assertTrue(modified.metadata.returnDirect)
        }

        @Test
        fun `withDescription result is DelegatingTool`() {
            val original = Tool.of(
                name = "test",
                description = "Original description",
            ) { Tool.Result.text("result") }

            val modified = original.withDescription("New description")

            assertTrue(modified is DelegatingTool)
        }
    }

    @Nested
    inner class WithNoteTest {

        @Test
        fun `withNote appends note to description`() {
            val original = Tool.of(
                name = "test",
                description = "Original description",
            ) { Tool.Result.text("result") }

            val modified = original.withNote("Additional context")

            assertEquals("Original description. Additional context", modified.definition.description)
        }

        @Test
        fun `withNote preserves tool name`() {
            val original = Tool.of(
                name = "my_tool",
                description = "Original description",
            ) { Tool.Result.text("result") }

            val modified = original.withNote("Note here")

            assertEquals("my_tool", modified.definition.name)
        }

        @Test
        fun `withNote preserves tool functionality`() {
            val original = Tool.of(
                name = "test",
                description = "Original description",
            ) { Tool.Result.text("expected result") }

            val modified = original.withNote("Note")
            val result = modified.call("{}")

            assertTrue(result is Tool.Result.Text)
            assertEquals("expected result", (result as Tool.Result.Text).content)
        }

        @Test
        fun `withNote can be chained`() {
            val original = Tool.of(
                name = "test",
                description = "Base",
            ) { Tool.Result.text("result") }

            val modified = original.withNote("Note 1").withNote("Note 2")

            assertEquals("Base. Note 1. Note 2", modified.definition.description)
        }

        @Test
        fun `withDescription and withNote can be combined`() {
            val original = Tool.of(
                name = "test",
                description = "Original",
            ) { Tool.Result.text("result") }

            val modified = original.withDescription("New base").withNote("Additional info")

            assertEquals("New base. Additional info", modified.definition.description)
        }
    }
}
