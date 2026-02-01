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
package com.embabel.agent.api.tool.state

import com.embabel.agent.api.tool.Tool
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

enum class TestState { INITIAL, PROCESSING, COMPLETE }

class StateMachineToolTest {

    private fun createTestTool(name: String, result: String = "result"): Tool {
        return mockk<Tool> {
            every { definition } returns object : Tool.Definition {
                override val name: String = name
                override val description: String = "Test tool $name"
                override val inputSchema: Tool.InputSchema = Tool.InputSchema.empty()
            }
            every { metadata } returns Tool.Metadata.DEFAULT
            every { call(any()) } returns Tool.Result.text(result)
        }
    }

    @Nested
    inner class Construction {

        @Test
        fun `should create StateMachineTool with name and description`() {
            val tool = StateMachineTool("test", "Test description", TestState::class.java)

            assertThat(tool.definition.name).isEqualTo("test")
            assertThat(tool.definition.description).isEqualTo("Test description")
            assertThat(tool.stateType).isEqualTo(TestState::class.java)
            assertThat(tool.initialState).isNull()
        }

        @Test
        fun `should set initial state with withInitialState`() {
            val tool = StateMachineTool("test", "Test", TestState::class.java)
                .withInitialState(TestState.INITIAL)

            assertThat(tool.initialState).isEqualTo(TestState.INITIAL)
        }

        @Test
        fun `should create tool starting in different state with startingIn`() {
            val tool = StateMachineTool("test", "Test", TestState::class.java)
                .withInitialState(TestState.INITIAL)

            val resumedTool = tool.startingIn(TestState.PROCESSING)

            assertThat((resumedTool as StateMachineTool<*>).initialState)
                .isEqualTo(TestState.PROCESSING)
        }

        @Test
        fun `should add tools to specific states`() {
            val processTool = createTestTool("process")
            val completeTool = createTestTool("complete")

            val stateMachine = StateMachineTool("test", "Test", TestState::class.java)
                .withInitialState(TestState.INITIAL)
                .inState(TestState.INITIAL)
                    .withTool(processTool).transitionsTo(TestState.PROCESSING)
                .inState(TestState.PROCESSING)
                    .withTool(completeTool).transitionsTo(TestState.COMPLETE)
                .build()

            assertThat(stateMachine.stateToolCounts).hasSize(2)
            assertThat(stateMachine.stateToolCounts[TestState.INITIAL]).isEqualTo(1)
            assertThat(stateMachine.stateToolCounts[TestState.PROCESSING]).isEqualTo(1)
        }

        @Test
        fun `should add global tools`() {
            val helpTool = createTestTool("help")
            val statusTool = createTestTool("status")

            val stateMachine = StateMachineTool("test", "Test", TestState::class.java)
                .withInitialState(TestState.INITIAL)
                .withGlobalTools(helpTool, statusTool)

            assertThat(stateMachine.globalTools).hasSize(2)
        }

        @Test
        fun `should support fluent API for multiple tools in same state`() {
            val tool1 = createTestTool("tool1")
            val tool2 = createTestTool("tool2")
            val tool3 = createTestTool("tool3")

            val stateMachine = StateMachineTool("test", "Test", TestState::class.java)
                .withInitialState(TestState.INITIAL)
                .inState(TestState.INITIAL)
                    .withTool(tool1)
                    .withTool(tool2)
                    .withTool(tool3).transitionsTo(TestState.PROCESSING)
                .build()

            assertThat(stateMachine.stateToolCounts[TestState.INITIAL]).isEqualTo(3)
        }
    }

    @Nested
    inner class StateHolderTests {

        @Test
        fun `should track current state`() {
            val holder = StateHolder(TestState.INITIAL)

            assertThat(holder.currentState).isEqualTo(TestState.INITIAL)
        }

        @Test
        fun `should transition to new state`() {
            val holder = StateHolder(TestState.INITIAL)

            holder.transitionTo(TestState.PROCESSING)

            assertThat(holder.currentState).isEqualTo(TestState.PROCESSING)
        }
    }

    @Nested
    inner class StateBoundToolTests {

        @Test
        fun `should execute when in correct state`() {
            val delegate = createTestTool("process", "processed")
            val stateHolder = StateHolder(TestState.INITIAL)
            val boundTool = StateBoundTool(
                delegate = delegate,
                availableInState = TestState.INITIAL,
                transitionsTo = null,
                stateHolder = stateHolder,
            )

            val result = boundTool.call("input")

            assertThat((result as Tool.Result.Text).content).isEqualTo("processed")
        }

        @Test
        fun `should return error message when in wrong state`() {
            val delegate = createTestTool("process")
            val stateHolder = StateHolder(TestState.PROCESSING)
            val boundTool = StateBoundTool(
                delegate = delegate,
                availableInState = TestState.INITIAL,
                transitionsTo = null,
                stateHolder = stateHolder,
            )

            val result = boundTool.call("input")

            assertThat((result as Tool.Result.Text).content)
                .contains("not available")
                .contains("PROCESSING")
                .contains("INITIAL")
        }

        @Test
        fun `should transition state after successful execution`() {
            val delegate = createTestTool("process")
            val stateHolder = StateHolder(TestState.INITIAL)
            val boundTool = StateBoundTool(
                delegate = delegate,
                availableInState = TestState.INITIAL,
                transitionsTo = TestState.PROCESSING,
                stateHolder = stateHolder,
            )

            boundTool.call("input")

            assertThat(stateHolder.currentState).isEqualTo(TestState.PROCESSING)
        }

        @Test
        fun `should not transition state when no transition configured`() {
            val delegate = createTestTool("process")
            val stateHolder = StateHolder(TestState.INITIAL)
            val boundTool = StateBoundTool(
                delegate = delegate,
                availableInState = TestState.INITIAL,
                transitionsTo = null,
                stateHolder = stateHolder,
            )

            boundTool.call("input")

            assertThat(stateHolder.currentState).isEqualTo(TestState.INITIAL)
        }

        @Test
        fun `should include state info in description`() {
            val delegate = createTestTool("process")
            val stateHolder = StateHolder(TestState.INITIAL)
            val boundTool = StateBoundTool(
                delegate = delegate,
                availableInState = TestState.INITIAL,
                transitionsTo = TestState.PROCESSING,
                stateHolder = stateHolder,
            )

            assertThat(boundTool.definition.description)
                .contains("INITIAL")
                .contains("PROCESSING")
        }
    }

    @Nested
    inner class GlobalStateToolTests {

        @Test
        fun `should execute in any state`() {
            val delegate = createTestTool("help", "help text")
            val stateHolder = StateHolder(TestState.PROCESSING)
            val globalTool = GlobalStateTool(delegate, stateHolder)

            val result = globalTool.call("input")

            assertThat((result as Tool.Result.Text).content).isEqualTo("help text")
        }

        @Test
        fun `should include global availability in description`() {
            val delegate = createTestTool("help")
            val stateHolder = StateHolder(TestState.INITIAL)
            val globalTool = GlobalStateTool(delegate, stateHolder)

            assertThat(globalTool.definition.description)
                .contains("all states")
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should return error when no initial state set`() {
            val tool = StateMachineTool("test", "Test", TestState::class.java)
            // No initial state set

            val result = tool.call("input")

            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
            assertThat((result as Tool.Result.Error).message).contains("initial state")
        }

        @Test
        fun `should return error when no AgentProcess context`() {
            val tool = StateMachineTool("test", "Test", TestState::class.java)
                .withInitialState(TestState.INITIAL)
                .inState(TestState.INITIAL)
                    .withTool(createTestTool("process")).build()
                .build()

            val result = tool.call("input")

            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
            assertThat((result as Tool.Result.Error).message).contains("AgentProcess")
        }
    }
}
