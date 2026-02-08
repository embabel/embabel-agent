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
package com.embabel.agent.tools.process

import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentProcessToolsTest {

    private fun createAgentProcess() = SimpleAgentProcess(
        id = "test",
        agent = SimpleTestAgent,
        processOptions = ProcessOptions(),
        blackboard = InMemoryBlackboard(),
        platformServices = dummyPlatformServices(),
        plannerFactory = DefaultPlannerFactory,
        parentId = null,
    )

    @Nested
    inner class MatryoshkaStructure {

        @Test
        fun `should create MatryoshkaTool with inner tools`() {
            val tool = AgentProcessTools().create()

            assertThat(tool).isInstanceOf(MatryoshkaTool::class.java)
            assertThat(tool.definition.name).isEqualTo("agent_process")

            val matryoshka = tool as MatryoshkaTool
            assertThat(matryoshka.innerTools).isNotEmpty()

            val toolNames = matryoshka.innerTools.map { it.definition.name }
            assertThat(toolNames).contains(
                "process_status",
                "process_budget",
                "process_cost",
                "process_history",
            )
        }
    }

    @Nested
    inner class StatusOperation {

        @Test
        fun `should show process status and runtime`() {
            val agentProcess = createAgentProcess()

            agentProcess.withCurrent {
                val tools = AgentProcessTools().create() as MatryoshkaTool
                val statusTool = tools.innerTools.find { it.definition.name == "process_status" }!!

                val result = statusTool.call("{}")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("Process ID:")
                assertThat(text).contains("Status:")
                assertThat(text).contains("Running time:")
            }
        }
    }

    @Nested
    inner class BudgetOperation {

        @Test
        fun `should show budget limits and usage`() {
            val agentProcess = createAgentProcess()

            agentProcess.withCurrent {
                val tools = AgentProcessTools().create() as MatryoshkaTool
                val budgetTool = tools.innerTools.find { it.definition.name == "process_budget" }!!

                val result = budgetTool.call("{}")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("Budget")
                assertThat(text).contains("Cost limit:")
                assertThat(text).contains("Action limit:")
            }
        }
    }

    @Nested
    inner class CostOperation {

        @Test
        fun `should show cost and token usage`() {
            val agentProcess = createAgentProcess()

            agentProcess.withCurrent {
                val tools = AgentProcessTools().create() as MatryoshkaTool
                val costTool = tools.innerTools.find { it.definition.name == "process_cost" }!!

                val result = costTool.call("{}")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("Cost")
                assertThat(text).contains("Total cost:")
                assertThat(text).contains("Tokens used:")
            }
        }
    }

    @Nested
    inner class HistoryOperation {

        @Test
        fun `should show action history`() {
            val agentProcess = createAgentProcess()

            agentProcess.withCurrent {
                val tools = AgentProcessTools().create() as MatryoshkaTool
                val historyTool = tools.innerTools.find { it.definition.name == "process_history" }!!

                val result = historyTool.call("{}")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                // May be empty for a fresh process
                assertThat(text).containsAnyOf("Action history", "No actions")
            }
        }
    }

    @Nested
    inner class NoAgentProcessContext {

        @Test
        fun `should return error when no AgentProcess available`() {
            val tools = AgentProcessTools().create() as MatryoshkaTool
            val statusTool = tools.innerTools.find { it.definition.name == "process_status" }!!

            val result = statusTool.call("{}")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            val text = (result as Tool.Result.Text).content
            assertThat(text).contains("No agent process")
        }
    }
}
