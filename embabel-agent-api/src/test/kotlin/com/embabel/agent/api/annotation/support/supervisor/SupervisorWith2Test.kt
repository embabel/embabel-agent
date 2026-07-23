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
package com.embabel.agent.api.annotation.support.supervisor

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.CurriedActionTool
import com.embabel.agent.api.annotation.support.SupervisorAction
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

class SupervisorWith2Test {

    private val objectMapper = ObjectMapper()

    @Test
    fun `supervisor action has tool actions for non-goal actions`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        // Get the supervisor action
        val supervisorAction = metadata.actions.first() as SupervisorAction

        // Verify it has tool actions
        assertEquals(1, supervisorAction.toolActions.size, "Should have 1 tool action for turnIntoFrog")

        // Verify the tool action is turnIntoFrog
        val toolAction = supervisorAction.toolActions.first()
        assertTrue(toolAction.shortName().contains("turnIntoFrog"), "Tool action should be turnIntoFrog")
    }

    @Test
    fun `curried tools are created from tool actions`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        val supervisorAction = metadata.actions.first() as SupervisorAction
        val blackboard = InMemoryBlackboard()

        // Create curried tools from the tool actions
        val curriedTools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )

        assertEquals(1, curriedTools.size, "Should have 1 curried tool")
        assertEquals("turnIntoFrog", curriedTools.first().definition.name)

        // The tool should require the UserInput parameter (not on blackboard yet)
        val params = curriedTools.first().definition.inputSchema.parameters
        assertTrue(params.isNotEmpty(), "Tool should have parameters when input not on blackboard")
    }

    @Test
    fun `curried tool has no parameters when input is on blackboard`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        val supervisorAction = metadata.actions.first() as SupervisorAction
        val blackboard = InMemoryBlackboard()

        // Put UserInput on the blackboard
        blackboard["it"] = UserInput("Kermit")

        // Create curried tools - should have no parameters now (curried out)
        val curriedTools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )

        assertEquals(1, curriedTools.size)
        val params = curriedTools.first().definition.inputSchema.parameters
        assertTrue(params.isEmpty(), "Tool should have no parameters when input is on blackboard")
        assertTrue(CurriedActionTool.isReady(curriedTools.first()), "Tool should be ready to execute")
    }

    @Test
    fun `tool returns error when called without AgentProcess context`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        val supervisorAction = metadata.actions.first() as SupervisorAction
        val blackboard = InMemoryBlackboard()

        val curriedTools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )

        // When called without an AgentProcess context, should return an error
        val result = curriedTools.first().call("""{"it": "TestInput"}""")
        assertTrue(
            result is com.embabel.agent.api.tool.Tool.Result.Error,
            "Tool should return error without context"
        )
    }

    @Test
    fun `supervisor agent metadata is read correctly`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2())
        assertNotNull(metadata)
        assertTrue(metadata is CoreAgent)
        metadata as CoreAgent
        // Supervisor agent consolidates all actions into a single supervisor action
        assertEquals(1, metadata.actions.size)
        assertTrue(metadata.actions.first().name.contains("supervisor"))
        assertEquals(1, metadata.goals.size)
    }

    @Test
    fun `supervisor action structure is correct`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        // Verify we have exactly 1 supervisor action, not the original 2 actions
        assertEquals(1, metadata.actions.size, "Should have exactly 1 supervisor action")
        val supervisorAction = metadata.actions.first()
        assertTrue(supervisorAction.name.contains("supervisor"), "Action should be the supervisor action")

        // Verify the supervisor action takes UserInput and produces Prince
        // (NOT Frog->Prince like the original turnIntoPrince action)
        assertEquals(1, supervisorAction.inputs.size)
        assertTrue(
            supervisorAction.inputs.first().type.contains("UserInput"),
            "Supervisor should take UserInput, not Frog"
        )
        assertEquals(1, supervisorAction.outputs.size)
        assertTrue(
            supervisorAction.outputs.first().type.contains("Prince"),
            "Supervisor should produce Prince"
        )
    }

    // Note: Full integration test requires a real LLM that actually calls tools.
    // The dummyAgentPlatform() doesn't simulate tool calling, so the supervisor
    // can't orchestrate the workflow. Use a real LLM integration test instead.
}
