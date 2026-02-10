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
package com.embabel.agent.api.tool.typed

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.TypedTool
import com.embabel.agent.core.ReplanRequestedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TypedToolTest {

    // Test fixtures
    data class AddRequest(val a: Int, val b: Int)
    data class AddResult(val sum: Int)

    data class GreetRequest(val name: String, val formal: Boolean = false)

    class AddTool : TypedTool<AddRequest, AddResult>(
        name = "add",
        description = "Add two numbers",
        inputType = AddRequest::class.java,
        outputType = AddResult::class.java,
    ) {
        override fun typedCall(input: AddRequest): AddResult {
            return AddResult(sum = input.a + input.b)
        }
    }

    class GreetTool : TypedTool<GreetRequest, String>(
        name = "greet",
        description = "Greet a person",
        inputType = GreetRequest::class.java,
        outputType = String::class.java,
    ) {
        override fun typedCall(input: GreetRequest): String {
            return if (input.formal) "Good day, ${input.name}" else "Hi ${input.name}!"
        }
    }

    class ToolResultTool : TypedTool<GreetRequest, Tool.Result>(
        name = "greet_result",
        description = "Greet returning Tool.Result directly",
        inputType = GreetRequest::class.java,
        outputType = Tool.Result::class.java,
    ) {
        override fun typedCall(input: GreetRequest): Tool.Result {
            return Tool.Result.text("Hello ${input.name}")
        }
    }

    class ReplanningTypedTool : TypedTool<GreetRequest, String>(
        name = "replan",
        description = "Always replans",
        inputType = GreetRequest::class.java,
        outputType = String::class.java,
    ) {
        override fun typedCall(input: GreetRequest): String {
            throw ReplanRequestedException("Need to replan for ${input.name}")
        }
    }

    @Nested
    inner class BasicFunctionality {

        @Test
        fun `typedCall receives deserialized input`() {
            val tool = AddTool()
            val result = tool.call("""{"a": 5, "b": 3}""")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).isEqualTo("""{"sum":8}""")
        }

        @Test
        fun `string return type is handled correctly`() {
            val tool = GreetTool()
            val result = tool.call("""{"name": "Alice", "formal": false}""")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).isEqualTo("Hi Alice!")
        }

        @Test
        fun `default parameter values work`() {
            val tool = GreetTool()
            val result = tool.call("""{"name": "Bob"}""")

            assertThat((result as Tool.Result.Text).content).isEqualTo("Hi Bob!")
        }

        @Test
        fun `Tool Result return type passes through`() {
            val tool = ToolResultTool()
            val result = tool.call("""{"name": "Charlie"}""")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).isEqualTo("Hello Charlie")
        }
    }

    @Nested
    inner class DefinitionGeneration {

        @Test
        fun `definition has correct name and description`() {
            val tool = AddTool()

            assertThat(tool.definition.name).isEqualTo("add")
            assertThat(tool.definition.description).isEqualTo("Add two numbers")
        }

        @Test
        fun `input schema is generated from input type`() {
            val tool = AddTool()
            val schema = tool.definition.inputSchema.toJsonSchema()

            assertThat(schema).contains("\"a\"")
            assertThat(schema).contains("\"b\"")
            assertThat(schema).contains("integer")
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `exceptions are converted to error results`() {
            val tool = object : TypedTool<GreetRequest, String>(
                name = "failing",
                description = "Always fails",
                inputType = GreetRequest::class.java,
                outputType = String::class.java,
            ) {
                override fun typedCall(input: GreetRequest): String {
                    throw IllegalStateException("Something went wrong")
                }
            }

            val result = tool.call("""{"name": "Test"}""")

            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
            assertThat((result as Tool.Result.Error).message).isEqualTo("Something went wrong")
        }

        @Test
        fun `invalid JSON produces error result`() {
            val tool = AddTool()
            val result = tool.call("not valid json")

            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
        }
    }

    @Nested
    inner class ControlFlowExceptions {

        @Test
        fun `ReplanRequestedException propagates through`() {
            val tool = ReplanningTypedTool()

            val exception = assertThrows<ReplanRequestedException> {
                tool.call("""{"name": "Test"}""")
            }

            assertThat(exception.reason).isEqualTo("Need to replan for Test")
        }
    }
}
