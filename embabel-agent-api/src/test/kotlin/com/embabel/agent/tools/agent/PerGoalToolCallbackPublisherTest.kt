/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.AutonomyProperties
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.dsl.evenMoreEvilWizardWithStructuredInput
import com.embabel.agent.api.dsl.userInputToFrogOrPersonBranch
import com.embabel.agent.testing.integration.IntegrationTestUtils
import com.embabel.agent.testing.integration.RandomRanker
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.junit.Test
import org.junit.jupiter.api.Assertions.*


class PerGoalToolCallbackPublisherTest {

    @Test
    fun `test user input function per goal`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(evenMoreEvilWizard())
        agentPlatform.deploy(userInputToFrogOrPersonBranch())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), AutonomyProperties())

        val provider = PerGoalToolCallbackPublisher(autonomy, jacksonObjectMapper(), mockk(), "testApp")

        val toolCallbacks = provider.toolCallbacks

        assertNotNull(toolCallbacks)
        assertEquals(
            autonomy.agentPlatform.goals.size + 1,
            toolCallbacks.size,
            "Should have one tool callback per goal plus continue"
        )

        for (toolCallback in toolCallbacks) {
            assertFalse(
                toolCallback.toolDefinition.inputSchema().contains("timestamp"),
                "Tool callback should not have timestamp in input schema: ${toolCallback.toolDefinition.inputSchema()}"
            )
            val toolDefinition = toolCallback.toolDefinition
            if (toolDefinition.name().contains(FORM_SUBMISSION_TOOL_NAME)) {
                // This is the form submission tool, which is a special case
                break
            }
            val goal = autonomy.agentPlatform.goals.find { toolCallback.toolDefinition.name().contains(it.name) }
            assertNotNull(
                goal,
                "Tool callback should correspond to a platform goal: Offending tool callback: $toolCallback"
            )
            assertNotNull(toolCallback.toolDefinition.inputSchema(), "Should have generated schema")
        }
    }

    @Test
    fun `test structured input type function for goal`() {
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform()
        agentPlatform.deploy(evenMoreEvilWizardWithStructuredInput())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), AutonomyProperties())

        val provider = PerGoalToolCallbackPublisher(autonomy, jacksonObjectMapper(), mockk(), "testApp")

        val toolCallbacks = provider.toolCallbacks

        assertNotNull(toolCallbacks)
        assertEquals(
            2 + 1, // 2 goals + 1 continuation
            toolCallbacks.size,
            "Should have 2 tool callback for the one goal plus continuation"
        )

        // Tool callbacks should have distinct names
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }
        assertEquals(
            toolNames.toSet().size,
            toolNames.size,
            "Tool callbacks should have distinct names: $toolNames"
        )

        for (toolCallback in toolCallbacks) {
            if (toolCallback.toolDefinition.name().contains(FORM_SUBMISSION_TOOL_NAME)) {
                // This is the form submission tool, which is a special case
                break
            }

            assertFalse(
                toolCallback.toolDefinition.inputSchema().contains("timestamp"),
                "Tool callback should not have timestamp in input schema: ${toolCallback.toolDefinition.inputSchema()}"
            )
            val toolDefinition = toolCallback.toolDefinition
            val goalName = toolDefinition.name()
            val goal = autonomy.agentPlatform.goals.find { toolDefinition.name().contains(it.name) }
            assertNotNull(
                goal,
                "Tool callback should correspond to a platform goal: $goalName, Offending tool callback: ${toolCallback.toolDefinition.name()}",
            )
            assertNotNull(toolCallback.toolDefinition.inputSchema(), "Should have generated schema")
        }
    }

}
