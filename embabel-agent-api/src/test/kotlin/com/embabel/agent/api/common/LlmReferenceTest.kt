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
package com.embabel.agent.api.common

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LlmReferenceTest {

    // Test fixtures for @LlmTool annotated classes
    class WeatherTools {
        @LlmTool(description = "Get the current weather")
        fun getWeather(city: String): String = "Sunny in $city"

        @LlmTool(description = "Get the forecast")
        fun getForecast(city: String, days: Int): String = "$days day forecast for $city"
    }

    class CalculatorTools {
        @LlmTool(description = "Add two numbers")
        fun add(a: Int, b: Int): Int = a + b
    }

    @Nested
    inner class FactoryMethodTests {

        @Test
        fun `of creates reference with name description tools and notes`() {
            val tool = Tool.of(
                name = "test_tool",
                description = "A test tool"
            ) { Tool.Result.text("ok") }

            val reference = LlmReference.of(
                name = "Test API",
                description = "A test API reference",
                tools = listOf(tool),
                notes = "Use this for testing"
            )

            assertThat(reference.name).isEqualTo("Test API")
            assertThat(reference.description).isEqualTo("A test API reference")
            assertThat(reference.notes()).isEqualTo("Use this for testing")
            assertThat(reference.tools()).hasSize(1)
            assertThat(reference.tools()[0].definition.name).isEqualTo("test_tool")
        }

        @Test
        fun `of creates reference with default empty notes`() {
            val tool = Tool.of("test_tool", "A test tool") { Tool.Result.text("ok") }

            val reference = LlmReference.of(
                name = "No Notes Ref",
                description = "Reference without notes",
                tools = listOf(tool)
            )

            assertThat(reference.notes()).isEmpty()
            assertThat(reference.tools()).hasSize(1)
        }

        @Test
        fun `of creates reference with empty tools list`() {
            val reference = LlmReference.of(
                name = "Simple Ref",
                description = "A simple reference",
                tools = emptyList(),
                notes = "No tools here"
            )

            assertThat(reference.name).isEqualTo("Simple Ref")
            assertThat(reference.tools()).isEmpty()
        }

        @Test
        fun `of creates reference with multiple tools`() {
            val tool1 = Tool.of("tool_one", "First tool") { Tool.Result.text("one") }
            val tool2 = Tool.of("tool_two", "Second tool") { Tool.Result.text("two") }
            val tool3 = Tool.of("tool_three", "Third tool") { Tool.Result.text("three") }

            val reference = LlmReference.of(
                name = "Multi Tool Ref",
                description = "Reference with multiple tools",
                tools = listOf(tool1, tool2, tool3),
                notes = "Has three tools"
            )

            assertThat(reference.tools()).hasSize(3)
            assertThat(reference.tools().map { it.definition.name })
                .containsExactly("tool_one", "tool_two", "tool_three")
        }

        @Test
        fun `created reference has valid tool prefix`() {
            val reference = LlmReference.of(
                name = "My API Reference",
                description = "Test",
                tools = emptyList()
            )

            val toolPrefix = reference.toolPrefix()
            assertThat(toolPrefix).isEqualTo("my api reference")
        }

        @Test
        fun `created reference generates valid contribution`() {
            val reference = LlmReference.of(
                name = "Test Ref",
                description = "A test reference",
                tools = emptyList(),
                notes = "These are the notes"
            )

            val contribution = reference.contribution()
            assertThat(contribution).contains("Test Ref")
            assertThat(contribution).contains("A test reference")
            assertThat(contribution).contains("These are the notes")
        }
    }

    @Nested
    inner class FromToolInstanceTests {

        @Test
        fun `fromToolInstance creates reference from single annotated object`() {
            val reference = LlmReference.of(
                name = "Weather",
                description = "Weather tools",
                tool = WeatherTools()
            )

            assertThat(reference.name).isEqualTo("Weather")
            assertThat(reference.tools()).hasSize(2)
            assertThat(reference.tools().map { it.definition.name })
                .containsExactlyInAnyOrder("getWeather", "getForecast")
        }

        @Test
        fun `fromToolInstance creates reference from Tool instance`() {
            val tool = Tool.of("my_tool", "A tool") { Tool.Result.text("ok") }

            val reference = LlmReference.of(
                name = "Single Tool",
                description = "One tool",
                tool = tool
            )

            assertThat(reference.tools()).hasSize(1)
            assertThat(reference.tools()[0].definition.name).isEqualTo("my_tool")
        }

        @Test
        fun `fromToolInstance with notes`() {
            val reference = LlmReference.of(
                name = "Calc",
                description = "Calculator",
                tool = CalculatorTools(),
                notes = "Use for math"
            )

            assertThat(reference.notes()).isEqualTo("Use for math")
            assertThat(reference.tools()).hasSize(1)
        }

        @Test
        fun `fromToolInstance defaults notes to empty`() {
            val reference = LlmReference.of(
                name = "Calc",
                description = "Calculator",
                tool = CalculatorTools()
            )

            assertThat(reference.notes()).isEmpty()
        }
    }

    @Nested
    inner class FromToolInstancesTests {

        @Test
        fun `fromToolInstances creates reference with tools from single instance`() {
            val reference = LlmReference.fromToolInstances(
                name = "Weather API",
                description = "Weather tools",
                notes = "Use these for weather queries",
                WeatherTools()
            )

            assertThat(reference.name).isEqualTo("Weather API")
            assertThat(reference.description).isEqualTo("Weather tools")
            assertThat(reference.notes()).isEqualTo("Use these for weather queries")
            assertThat(reference.tools()).hasSize(2)
            assertThat(reference.tools().map { it.definition.name })
                .containsExactlyInAnyOrder("getWeather", "getForecast")
        }

        @Test
        fun `fromToolInstances creates reference with tools from multiple instances`() {
            val reference = LlmReference.fromToolInstances(
                name = "Multi Tools",
                description = "Multiple tool sources",
                notes = "Combined tools",
                WeatherTools(),
                CalculatorTools()
            )

            assertThat(reference.tools()).hasSize(3)
            assertThat(reference.tools().map { it.definition.name })
                .containsExactlyInAnyOrder("getWeather", "getForecast", "add")
        }

        @Test
        fun `fromToolInstances tools are callable`() {
            val reference = LlmReference.fromToolInstances(
                name = "Calc",
                description = "Calculator",
                notes = "Math tools",
                CalculatorTools()
            )

            val addTool = reference.tools().first { it.definition.name == "add" }
            val result = addTool.call("""{"a": 5, "b": 3}""")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).isEqualTo("8")
        }

        @Test
        fun `fromToolInstances accepts Tool instance directly`() {
            val tool = Tool.of("test_tool", "A test tool") { Tool.Result.text("ok") }

            val reference = LlmReference.fromToolInstances(
                name = "Direct Tool",
                description = "Direct tool reference",
                notes = "Has a direct tool",
                tool
            )

            assertThat(reference.tools()).hasSize(1)
            assertThat(reference.tools()[0].definition.name).isEqualTo("test_tool")
        }

        @Test
        fun `fromToolInstances accepts mix of Tool instances and annotated objects`() {
            val tool = Tool.of("direct_tool", "A direct tool") { Tool.Result.text("ok") }

            val reference = LlmReference.fromToolInstances(
                name = "Mixed",
                description = "Mixed types",
                notes = "Both types work",
                WeatherTools(),
                tool
            )

            assertThat(reference.tools()).hasSize(3)
            assertThat(reference.tools().map { it.definition.name })
                .containsExactlyInAnyOrder("getWeather", "getForecast", "direct_tool")
        }
    }

    @Nested
    inner class AsMatryoshkaTests {

        @Test
        fun `does not rewrap`() {
            val tool = Tool.of("simple_tool", "A simple tool") { Tool.Result.text("ok") }

            val reference = LlmReference.of(
                name = "Simple Ref",
                description = "A simple reference",
                tools = listOf(tool)
            )

            val matryoshka1 = reference.asMatryoshka()

            assertThat(matryoshka1.asMatryoshka()).isSameAs(matryoshka1)
            assertThat(matryoshka1.asMatryoshka().asMatryoshka()).isSameAs(matryoshka1)
        }

        @Test
        fun `asMatryoshka returns reference with single MatryoshkaTool containing all original tools`() {
            val tool1 = Tool.of("tool_one", "First tool") { Tool.Result.text("one") }
            val tool2 = Tool.of("tool_two", "Second tool") { Tool.Result.text("two") }

            val reference = LlmReference.of(
                name = "Test API",
                description = "A test API reference",
                tools = listOf(tool1, tool2),
                notes = "Use this for testing"
            )

            val matryoshka = reference.asMatryoshka()

            assertThat(matryoshka.tools()).hasSize(1)
            val outerTool = matryoshka.tools()[0]
            assertThat(outerTool).isInstanceOf(MatryoshkaTool::class.java)
            val matryoshkaTool = outerTool as MatryoshkaTool
            assertThat(matryoshkaTool.innerTools).hasSize(2)
            assertThat(matryoshkaTool.innerTools.map { it.definition.name })
                .containsExactlyInAnyOrder("tool_one", "tool_two")
        }

        @Test
        fun `asMatryoshka preserves contribution from original reference`() {
            val tool = Tool.of("test_tool", "Test") { Tool.Result.text("ok") }

            val reference = LlmReference.of(
                name = "My API",
                description = "API description",
                tools = listOf(tool),
                notes = "Important notes"
            )

            val matryoshka = reference.asMatryoshka()

            assertThat(matryoshka.contribution()).isEqualTo(reference.contribution())
            assertThat(matryoshka.contribution()).contains("My API")
            assertThat(matryoshka.contribution()).contains("API description")
            assertThat(matryoshka.contribution()).contains("Important notes")
        }

        @Test
        fun `asMatryoshka preserves name and description`() {
            val reference = LlmReference.of(
                name = "Test Ref",
                description = "Reference description",
                tools = listOf(Tool.of("t", "t") { Tool.Result.text("ok") })
            )

            val matryoshka = reference.asMatryoshka()

            assertThat(matryoshka.name).isEqualTo("Test Ref")
            assertThat(matryoshka.description).isEqualTo("Reference description")
        }

        @Test
        fun `asMatryoshka uses tool prefix as MatryoshkaTool name`() {
            val reference = LlmReference.of(
                name = "Weather API",
                description = "Weather tools",
                tools = listOf(Tool.of("get_weather", "Get weather") { Tool.Result.text("sunny") })
            )

            val matryoshka = reference.asMatryoshka()

            val matryoshkaTool = matryoshka.tools()[0] as MatryoshkaTool
            assertThat(matryoshkaTool.definition.name).isEqualTo(reference.toolPrefix())
        }

        @Test
        fun `asMatryoshka returns empty tools when original has no tools`() {
            val reference = LlmReference.of(
                name = "Empty Ref",
                description = "No tools",
                tools = emptyList()
            )

            val matryoshka = reference.asMatryoshka()

            assertThat(matryoshka.tools()).isEmpty()
        }

        @Test
        fun `asMatryoshka returns empty toolInstances`() {
            val reference = LlmReference.of(
                name = "Test",
                description = "Test",
                tools = listOf(Tool.of("t", "t") { Tool.Result.text("ok") })
            )

            val matryoshka = reference.asMatryoshka()

            assertThat(matryoshka.toolInstances()).isEmpty()
        }
    }
}
