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

import com.embabel.agent.core.AgentProcess
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolUtilsTest {

    @Nested
    inner class ReplanAlwaysTests {

        private val mockAgentProcess = mockk<AgentProcess>()

        @BeforeEach
        fun setUp() {
            AgentProcess.set(mockAgentProcess)
        }

        @AfterEach
        fun tearDown() {
            AgentProcess.remove()
        }

        @Test
        fun `replanAlways throws ReplanRequestedException on any call`() {
            val tool = Tool.of("my_tool", "A tool") { Tool.Result.text("result") }
            val replanningTool = ToolUtils.replanAlways(tool)

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            assertThat(exception.reason).contains("my_tool")
            assertThat(exception.reason).contains("replans")
        }
    }

    @Nested
    inner class ReplanWhenTests {

        private val mockAgentProcess = mockk<AgentProcess>()

        @BeforeEach
        fun setUp() {
            AgentProcess.set(mockAgentProcess)
        }

        @AfterEach
        fun tearDown() {
            AgentProcess.remove()
        }

        @Test
        fun `replanWhen with predicate triggers replan when predicate matches`() {
            data class Score(val value: Int)

            val tool = Tool.of("scorer", "Scores things") {
                Tool.Result.WithArtifact("Score: 95", Score(95))
            }

            val replanningTool = ToolUtils.replanWhen(
                tool = tool,
                predicate = { score: Score -> score.value > 90 }
            )

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            assertThat(exception.reason).contains("scorer")
        }

        @Test
        fun `replanWhen with predicate returns normally when predicate does not match`() {
            data class Score(val value: Int)

            val tool = Tool.of("scorer", "Scores things") {
                Tool.Result.WithArtifact("Score: 50", Score(50))
            }

            val replanningTool = ToolUtils.replanWhen(
                tool = tool,
                predicate = { score: Score -> score.value > 90 }
            )

            val result = replanningTool.call("{}")

            assertThat((result as Tool.Result.WithArtifact).content).isEqualTo("Score: 50")
        }

        @Test
        fun `replanWhen with predicate returns normally when artifact is wrong type`() {
            val tool = Tool.of("tool", "A tool") {
                Tool.Result.WithArtifact("content", "string artifact")
            }

            val replanningTool = ToolUtils.replanWhen(
                tool = tool,
                predicate = { num: Int -> num > 10 }
            )

            val result = replanningTool.call("{}")

            assertThat((result as Tool.Result.WithArtifact).content).isEqualTo("content")
        }

        @Test
        fun `replanWhen with predicate returns normally when no artifact`() {
            val tool = Tool.of("tool", "A tool") {
                Tool.Result.text("just text")
            }

            val replanningTool = ToolUtils.replanWhen(
                tool = tool,
                predicate = { _: Any -> true }
            )

            val result = replanningTool.call("{}")

            assertThat((result as Tool.Result.Text).content).isEqualTo("just text")
        }

        @Test
        fun `conditionalReplan with decider triggers replan with custom decision`() {
            data class Routing(val target: String, val confidence: Double)

            val tool = Tool.of("router", "Routes requests") {
                Tool.Result.WithArtifact("Routing to support", Routing("support", 0.95))
            }

            val replanningTool = ToolUtils.conditionalReplan<Routing>(
                tool = tool,
            ) { routing, _ ->
                if (routing.confidence > 0.9) {
                    ReplanDecision(
                        reason = "High confidence routing to ${routing.target}",
                        blackboardUpdates = mapOf("target" to routing.target)
                    )
                } else null
            }

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            assertThat(exception.reason).isEqualTo("High confidence routing to support")
            assertThat(exception.blackboardUpdates["target"]).isEqualTo("support")
        }

        @Test
        fun `conditionalReplan with decider returns normally when decider returns null`() {
            data class Routing(val target: String, val confidence: Double)

            val tool = Tool.of("router", "Routes requests") {
                Tool.Result.WithArtifact("Low confidence", Routing("support", 0.5))
            }

            val replanningTool = ToolUtils.conditionalReplan<Routing>(
                tool = tool,
            ) { routing, _ ->
                if (routing.confidence > 0.9) {
                    ReplanDecision("High confidence")
                } else null
            }

            val result = replanningTool.call("{}")

            assertThat((result as Tool.Result.WithArtifact).content).isEqualTo("Low confidence")
        }

        @Test
        fun `conditionalReplan with decider has access to ReplanContext`() {
            data class Data(val x: Int)

            val tool = Tool.of("context_tool", "Uses context") {
                Tool.Result.WithArtifact("content", Data(42))
            }

            var capturedToolName: String? = null
            val replanningTool = ToolUtils.conditionalReplan<Data>(
                tool = tool,
            ) { _, context ->
                capturedToolName = context.tool.definition.name
                ReplanDecision("Got context")
            }

            assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            assertThat(capturedToolName).isEqualTo("context_tool")
        }
    }

    @Nested
    inner class FormatToolTreeTests {

        @Test
        fun `returns message for empty tool list`() {
            val result = ToolUtils.formatToolTree("MyAgent", emptyList())

            assertThat(result).isEqualTo("MyAgent has no tools")
        }

        @Test
        fun `formats single tool`() {
            val tool = simpleTool("get_weather")

            val result = ToolUtils.formatToolTree("MyAgent", listOf(tool))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── get_weather
                """.trimIndent()
            )
        }

        @Test
        fun `formats multiple tools`() {
            val tools = listOf(
                simpleTool("get_weather"),
                simpleTool("send_email"),
                simpleTool("read_file")
            )

            val result = ToolUtils.formatToolTree("MyAgent", tools)

            assertThat(result).isEqualTo(
                """
                MyAgent
                ├── get_weather
                ├── send_email
                └── read_file
                """.trimIndent()
            )
        }

        @Test
        fun `formats MatryoshkaTool with inner tools`() {
            val innerTools = listOf(
                simpleTool("query_database"),
                simpleTool("insert_record")
            )
            val matryoshka = MatryoshkaTool.of(
                name = "database_ops",
                description = "Database operations",
                innerTools = innerTools
            )

            val result = ToolUtils.formatToolTree("MyAgent", listOf(matryoshka))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── database_ops (2 inner tools)
                    ├── query_database
                    └── insert_record
                """.trimIndent()
            )
        }

        @Test
        fun `formats mixed regular and MatryoshkaTools`() {
            val dbInnerTools = listOf(
                simpleTool("query"),
                simpleTool("insert")
            )
            val dbTool = MatryoshkaTool.of(
                name = "database",
                description = "Database ops",
                innerTools = dbInnerTools
            )

            val tools = listOf(
                simpleTool("get_weather"),
                dbTool,
                simpleTool("send_email")
            )

            val result = ToolUtils.formatToolTree("MyAgent", tools)

            assertThat(result).isEqualTo(
                """
                MyAgent
                ├── get_weather
                ├── database (2 inner tools)
                │   ├── query
                │   └── insert
                └── send_email
                """.trimIndent()
            )
        }

        @Test
        fun `formats MatryoshkaTool with no inner tools`() {
            val matryoshka = MatryoshkaTool.of(
                name = "empty_group",
                description = "Empty group",
                innerTools = emptyList()
            )

            val result = ToolUtils.formatToolTree("MyAgent", listOf(matryoshka))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── empty_group (0 inner tools)
                """.trimIndent()
            )
        }

        @Test
        fun `formats nested MatryoshkaTools recursively`() {
            val innerMost = listOf(
                simpleTool("deep_tool_1"),
                simpleTool("deep_tool_2")
            )
            val nestedMatryoshka = MatryoshkaTool.of(
                name = "nested_group",
                description = "Nested group",
                innerTools = innerMost
            )
            val outerMatryoshka = MatryoshkaTool.of(
                name = "outer_group",
                description = "Outer group",
                innerTools = listOf(simpleTool("sibling"), nestedMatryoshka)
            )

            val result = ToolUtils.formatToolTree("MyAgent", listOf(outerMatryoshka))

            assertThat(result).isEqualTo(
                """
                MyAgent
                └── outer_group (2 inner tools)
                    ├── sibling
                    └── nested_group (2 inner tools)
                        ├── deep_tool_1
                        └── deep_tool_2
                """.trimIndent()
            )
        }

        private fun simpleTool(name: String): Tool {
            return Tool.of(
                name = name,
                description = "Test tool $name"
            ) { Tool.Result.text("ok") }
        }
    }
}
