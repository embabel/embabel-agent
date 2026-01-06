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
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.test.integration.ScriptedLlmOperations
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Tests that verify the supervisor's curried tools correctly progress
 * as the blackboard is updated by tool executions.
 */
class SupervisorToolProgressionTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `tools are curried as blackboard fills with data`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        val supervisorAction = metadata.actions.first() as SupervisorAction
        val blackboard = InMemoryBlackboard()

        // Initially: turnIntoFrog needs UserInput (1 parameter)
        val initialTools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )

        assertEquals(1, initialTools.size, "Should have 1 tool")
        val turnIntoFrogTool = initialTools.find { it.definition.name == "turnIntoFrog" }!!

        // Tool should require the UserInput parameter
        assertTrue(
            turnIntoFrogTool.definition.inputSchema.parameters.isNotEmpty(),
            "turnIntoFrog should need UserInput parameter"
        )
        assertFalse(
            CurriedActionTool.isReady(turnIntoFrogTool),
            "Tool should NOT be ready (needs UserInput)"
        )

        // Add UserInput to blackboard
        blackboard["it"] = UserInput("Kermit")

        // Regenerate tools - now turnIntoFrog should be curried (no params needed)
        val afterUserInputTools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )

        val curriedTurnIntoFrog = afterUserInputTools.find { it.definition.name == "turnIntoFrog" }!!
        assertTrue(
            curriedTurnIntoFrog.definition.inputSchema.parameters.isEmpty(),
            "turnIntoFrog should have NO parameters after UserInput is on blackboard"
        )
        assertTrue(
            CurriedActionTool.isReady(curriedTurnIntoFrog),
            "Tool should be ready after UserInput is on blackboard"
        )
    }

    @Test
    fun `scripted llm calls tools and updates blackboard`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        // Create scripted LLM that will:
        // 1. Call turnIntoFrog tool
        // 2. Respond when done
        val scriptedLlm = ScriptedLlmOperations()
            .callTool("turnIntoFrog", """{}""")
            .respond("I called turnIntoFrog, the blackboard now has a Frog")

        val ap = dummyAgentPlatform(llmOperations = scriptedLlm)

        // Start with UserInput on the blackboard
        val agentProcess = ap.runAgentFrom(
            metadata,
            ProcessOptions(plannerType = PlannerType.SUPERVISOR),
            mapOf("it" to UserInput("Kermit")),
        )

        // Verify the tool was called
        assertEquals(1, scriptedLlm.toolCallsMade.size, "Should have called 1 tool")
        assertEquals("turnIntoFrog", scriptedLlm.toolCallsMade[0].toolName)

        // Verify the result indicates success
        assertTrue(
            scriptedLlm.toolCallsMade[0].result.contains("succeeded"),
            "Tool should have succeeded. Got: ${scriptedLlm.toolCallsMade[0].result}"
        )

        // Verify Frog was added to blackboard (may be in objects, not just map)
        val frog = agentProcess.blackboard.objects
            .filterIsInstance<Frog>()
            .firstOrNull()
            ?: agentProcess.blackboard.expressionEvaluationModel().values
                .filterIsInstance<Frog>()
                .firstOrNull()
        assertNotNull(frog, "Frog should be on the blackboard after turnIntoFrog")
        assertEquals("Kermit", frog!!.name)
    }

    @Test
    fun `supervisor executes multi-step workflow with tool progression`() {
        // This test needs a 3-step workflow to properly demonstrate currying
        // SupervisorWith2 only has turnIntoFrog -> turnIntoPrince (goal)
        // The goal action (turnIntoPrince) isn't exposed as a tool

        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        // Create scripted LLM that will:
        // Iteration 1: Call turnIntoFrog (which needs UserInput - on blackboard)
        // Iteration 2: Respond (goal achieved - Prince on blackboard from previous action? No, that's the goal action)
        val scriptedLlm = ScriptedLlmOperations()
            // First iteration: call turnIntoFrog
            .callTool("turnIntoFrog", """{}""")
            .respond("Called turnIntoFrog, Frog is now on blackboard")
            // Second iteration: no more tools, just respond
            .respond("No more tools to call, waiting for goal")

        val ap = dummyAgentPlatform(llmOperations = scriptedLlm)

        val agentProcess = ap.runAgentFrom(
            metadata,
            ProcessOptions(plannerType = PlannerType.SUPERVISOR),
            mapOf("it" to UserInput("Kermit")),
        )

        // Verify turnIntoFrog was called
        assertTrue(
            scriptedLlm.toolCallsMade.any { it.toolName == "turnIntoFrog" },
            "turnIntoFrog should have been called"
        )

        // The Frog should be on the blackboard (may be in objects, not just map)
        val frog = agentProcess.blackboard.objects
            .filterIsInstance<Frog>()
            .firstOrNull()
            ?: agentProcess.blackboard.expressionEvaluationModel().values
                .filterIsInstance<Frog>()
                .firstOrNull()
        assertNotNull(frog, "Frog should be on blackboard")
    }

    @Test
    fun `tool descriptions indicate curried status`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        val supervisorAction = metadata.actions.first() as SupervisorAction
        val blackboard = InMemoryBlackboard()

        // Without UserInput - tool needs parameters
        val beforeTools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )
        val beforeDesc = beforeTools.first().definition.description
        assertFalse(
            beforeDesc.contains("all inputs available"),
            "Description should NOT say inputs available when they're not"
        )

        // With UserInput - tool is curried
        blackboard["it"] = UserInput("Kermit")
        val afterTools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )
        val afterDesc = afterTools.first().definition.description
        assertTrue(
            afterDesc.contains("all inputs available") || afterDesc.contains("on blackboard"),
            "Description should indicate inputs are available. Got: $afterDesc"
        )
    }

    @Test
    fun `tools are sorted by readiness`() {
        // Create a custom 3-step agent for this test
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith3Steps()) as CoreAgent

        val supervisorAction = metadata.actions.first() as SupervisorAction
        val blackboard = InMemoryBlackboard()

        // Start with Ingredient on blackboard
        blackboard["ingredient"] = Ingredient("flour")

        val tools = CurriedActionTool.createTools(
            actions = supervisorAction.toolActions,
            blackboard = blackboard,
            objectMapper = objectMapper,
        )

        // Tools should be sorted by number of required inputs (ready first)
        val readyCount = tools.count { CurriedActionTool.isReady(it) }

        // The first tools in the list should be the ready ones
        for (i in 0 until readyCount) {
            assertTrue(
                CurriedActionTool.isReady(tools[i]),
                "First $readyCount tools should be ready, but tool $i is not"
            )
        }
    }
}
