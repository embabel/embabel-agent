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

import com.embabel.agent.core.ReplanRequestedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolFromFunctionTest {

    // Test fixtures
    data class AddRequest(val a: Int, val b: Int)
    data class AddResult(val sum: Int)

    data class GreetRequest(val name: String, val formal: Boolean = false)

    @Nested
    inner class KotlinApi {

        @Test
        fun `creates tool with correct definition`() {
            val tool = Tool.fromFunction<AddRequest, AddResult>(
                name = "add",
                description = "Add two numbers",
            ) { input -> AddResult(input.a + input.b) }

            assertThat(tool.definition.name).isEqualTo("add")
            assertThat(tool.definition.description).isEqualTo("Add two numbers")
        }

        @Test
        fun `generates input schema from type`() {
            val tool = Tool.fromFunction<AddRequest, AddResult>(
                name = "add",
                description = "Add numbers",
            ) { input -> AddResult(input.a + input.b) }

            val schema = tool.definition.inputSchema.toJsonSchema()
            assertThat(schema).contains("\"a\"")
            assertThat(schema).contains("\"b\"")
        }

        @Test
        fun `deserializes input and serializes output`() {
            val tool = Tool.fromFunction<AddRequest, AddResult>(
                name = "add",
                description = "Add numbers",
            ) { input -> AddResult(input.a + input.b) }

            val result = tool.call("""{"a": 5, "b": 3}""")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            assertThat((result as Tool.Result.Text).content).isEqualTo("""{"sum":8}""")
        }

        @Test
        fun `string output is not double-serialized`() {
            val tool = Tool.fromFunction<GreetRequest, String>(
                name = "greet",
                description = "Greet",
            ) { input -> "Hello ${input.name}!" }

            val result = tool.call("""{"name": "Alice"}""")

            assertThat((result as Tool.Result.Text).content).isEqualTo("Hello Alice!")
        }

        @Test
        fun `Tool Result passes through`() {
            val tool = Tool.fromFunction<GreetRequest, Tool.Result>(
                name = "greet",
                description = "Greet",
            ) { input -> Tool.Result.text("Hi ${input.name}") }

            val result = tool.call("""{"name": "Bob"}""")

            assertThat((result as Tool.Result.Text).content).isEqualTo("Hi Bob")
        }

        @Test
        fun `exceptions become error results`() {
            val tool = Tool.fromFunction<GreetRequest, String>(
                name = "fail",
                description = "Always fails",
            ) { throw IllegalStateException("Oops") }

            val result = tool.call("""{"name": "Test"}""")

            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
            assertThat((result as Tool.Result.Error).message).isEqualTo("Oops")
        }

        @Test
        fun `ReplanRequestedException propagates`() {
            val tool = Tool.fromFunction<GreetRequest, String>(
                name = "replan",
                description = "Replans",
            ) { input -> throw ReplanRequestedException("Need to replan for ${input.name}") }

            val exception = assertThrows<ReplanRequestedException> {
                tool.call("""{"name": "Test"}""")
            }
            assertThat(exception.reason).isEqualTo("Need to replan for Test")
        }

        @Test
        fun `default parameters work`() {
            val tool = Tool.fromFunction<GreetRequest, String>(
                name = "greet",
                description = "Greet",
            ) { input -> if (input.formal) "Good day" else "Hi" }

            val result = tool.call("""{"name": "Alice"}""")
            assertThat((result as Tool.Result.Text).content).isEqualTo("Hi")
        }
    }

    @Nested
    inner class JavaApi {

        @Test
        fun `creates tool with explicit types`() {
            val tool = Tool.fromFunction(
                "add",
                "Add two numbers",
                AddRequest::class.java,
                AddResult::class.java,
                java.util.function.Function { input: AddRequest -> AddResult(input.a + input.b) }
            )

            assertThat(tool.definition.name).isEqualTo("add")

            val result = tool.call("""{"a": 10, "b": 20}""")
            assertThat((result as Tool.Result.Text).content).isEqualTo("""{"sum":30}""")
        }

        @Test
        fun `with metadata`() {
            val tool = Tool.fromFunction(
                "add",
                "Add numbers",
                AddRequest::class.java,
                AddResult::class.java,
                Tool.Metadata.create(returnDirect = true),
                java.util.function.Function { input: AddRequest -> AddResult(input.a + input.b) }
            )

            assertThat(tool.metadata.returnDirect).isTrue()
        }
    }
}
