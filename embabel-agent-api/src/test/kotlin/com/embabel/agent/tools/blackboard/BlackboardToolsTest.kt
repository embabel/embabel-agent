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
package com.embabel.agent.tools.blackboard

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.core.types.HasInfoString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Test domain classes
data class TestPerson(val name: String, val age: Int) : HasInfoString {
    override fun infoString(verbose: Boolean?, indent: Int): String =
        "Person: $name, age $age"
}

data class TestOrder(val orderId: String, val total: Double)

class BlackboardToolsTest {

    private fun createAgentProcess(blackboard: InMemoryBlackboard) = SimpleAgentProcess(
        id = "test",
        agent = SimpleTestAgent,
        processOptions = ProcessOptions(),
        blackboard = blackboard,
        platformServices = dummyPlatformServices(),
        plannerFactory = DefaultPlannerFactory,
        parentId = null,
    )

    @Nested
    inner class UnfoldingStructure {

        @Test
        fun `should create UnfoldingTool with inner tools`() {
            val tool = BlackboardTools().create()

            assertThat(tool).isInstanceOf(UnfoldingTool::class.java)
            assertThat(tool.definition.name).isEqualTo("blackboard")

            val unfolding = tool as UnfoldingTool
            assertThat(unfolding.innerTools).isNotEmpty()

            val toolNames = unfolding.innerTools.map { it.definition.name }
            assertThat(toolNames).contains("blackboard_list", "blackboard_get", "blackboard_last", "blackboard_describe")
        }
    }

    @Nested
    inner class ListOperation {

        @Test
        fun `should list all objects in blackboard`() {
            val blackboard = InMemoryBlackboard()
            blackboard += TestPerson("Alice", 30)
            blackboard += TestOrder("ORD-123", 99.99)

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val listTool = tools.innerTools.find { it.definition.name == "blackboard_list" }!!

                val result = listTool.call("{}")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("TestPerson")
                assertThat(text).contains("TestOrder")
            }
        }

        @Test
        fun `should show empty message when blackboard is empty`() {
            val blackboard = InMemoryBlackboard()
            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val listTool = tools.innerTools.find { it.definition.name == "blackboard_list" }!!

                val result = listTool.call("{}")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("empty")
            }
        }
    }

    @Nested
    inner class GetByNameOperation {

        @Test
        fun `should get object by binding name`() {
            val blackboard = InMemoryBlackboard()
            val person = TestPerson("Bob", 25)
            blackboard["user"] = person

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val getTool = tools.innerTools.find { it.definition.name == "blackboard_get" }!!

                val result = getTool.call("""{"name": "user"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("Bob")
            }
        }

        @Test
        fun `should return not found for missing name`() {
            val blackboard = InMemoryBlackboard()
            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val getTool = tools.innerTools.find { it.definition.name == "blackboard_get" }!!

                val result = getTool.call("""{"name": "nonexistent"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("No")
            }
        }
    }

    @Nested
    inner class GetLastOperation {

        @Test
        fun `should get last object of type by simple name`() {
            val blackboard = InMemoryBlackboard()
            blackboard += TestPerson("First", 20)
            blackboard += TestOrder("ORD-1", 10.0)
            blackboard += TestPerson("Last", 40)

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val lastTool = tools.innerTools.find { it.definition.name == "blackboard_last" }!!

                val result = lastTool.call("""{"typeName": "TestPerson"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("Last")
                assertThat(text).doesNotContain("First")
            }
        }

        @Test
        fun `should get last object of type by FQN`() {
            val blackboard = InMemoryBlackboard()
            blackboard += TestPerson("Alice", 30)

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val lastTool = tools.innerTools.find { it.definition.name == "blackboard_last" }!!

                val result = lastTool.call("""{"typeName": "com.embabel.agent.tools.blackboard.TestPerson"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("Alice")
            }
        }

        @Test
        fun `should return not found for missing type`() {
            val blackboard = InMemoryBlackboard()
            blackboard += TestOrder("ORD-1", 10.0)

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val lastTool = tools.innerTools.find { it.definition.name == "blackboard_last" }!!

                val result = lastTool.call("""{"typeName": "TestPerson"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("No")
            }
        }
    }

    @Nested
    inner class DescribeOperation {

        @Test
        fun `should describe object using HasInfoString`() {
            val blackboard = InMemoryBlackboard()
            val person = TestPerson("Charlie", 35)
            blackboard["person"] = person

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val describeTool = tools.innerTools.find { it.definition.name == "blackboard_describe" }!!

                val result = describeTool.call("""{"name": "person"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("Person: Charlie, age 35")
            }
        }

        @Test
        fun `should describe object using toString for non-HasInfoString`() {
            val blackboard = InMemoryBlackboard()
            val order = TestOrder("ORD-999", 150.0)
            blackboard["order"] = order

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val describeTool = tools.innerTools.find { it.definition.name == "blackboard_describe" }!!

                val result = describeTool.call("""{"name": "order"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("ORD-999")
                assertThat(text).contains("150.0")
            }
        }
    }

    @Nested
    inner class CountOperation {

        @Test
        fun `should count objects of type`() {
            val blackboard = InMemoryBlackboard()
            blackboard += TestPerson("A", 1)
            blackboard += TestPerson("B", 2)
            blackboard += TestOrder("O1", 10.0)
            blackboard += TestPerson("C", 3)

            val agentProcess = createAgentProcess(blackboard)

            agentProcess.withCurrent {
                val tools = BlackboardTools().create() as UnfoldingTool
                val countTool = tools.innerTools.find { it.definition.name == "blackboard_count" }!!

                val result = countTool.call("""{"typeName": "TestPerson"}""")

                assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
                val text = (result as Tool.Result.Text).content
                assertThat(text).contains("3")
            }
        }
    }

    @Nested
    inner class NoAgentProcessContext {

        @Test
        fun `should return error when no AgentProcess available`() {
            // Ensure no AgentProcess is set
            val tools = BlackboardTools().create() as UnfoldingTool
            val listTool = tools.innerTools.find { it.definition.name == "blackboard_list" }!!

            val result = listTool.call("{}")

            assertThat(result).isInstanceOf(Tool.Result.Text::class.java)
            val text = (result as Tool.Result.Text).content
            assertThat(text).contains("No agent process")
        }
    }
}
