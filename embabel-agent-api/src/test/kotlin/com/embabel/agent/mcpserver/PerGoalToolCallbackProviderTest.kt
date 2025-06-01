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
package com.embabel.agent.mcpserver

import com.embabel.agent.api.common.Autonomy
import com.embabel.agent.api.common.AutonomyProperties
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.dsl.userInputToFrogOrPersonBranch
import com.embabel.agent.testing.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.testing.RandomRanker
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

class PerGoalToolCallbackProviderTest {

    @Test
    fun `test function per goal`() {
        val agentPlatform = dummyAgentPlatform()
        agentPlatform.deploy(evenMoreEvilWizard())
        agentPlatform.deploy(userInputToFrogOrPersonBranch())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), AutonomyProperties())

        val provider = PerGoalToolCallbackProvider(autonomy, jacksonObjectMapper())

        // Act
        val toolCallbacks = provider.toolCallbacks

        // Assert
        assertNotNull(toolCallbacks)
        assertEquals(autonomy.agentPlatform.goals.size, toolCallbacks.size, "Should have one tool callback per goal")

        // Check that each tool callback corresponds to a goal
        for (toolCallback in toolCallbacks) {
            val toolDefinition = toolCallback.toolDefinition
            val goalName = toolDefinition.name()
            val goal = autonomy.agentPlatform.goals.find { it.name == goalName }
            assertNotNull(goal, "Tool callback should correspond to a platform goal")
            assertNotNull(toolCallback.toolDefinition.inputSchema(), "Should have generated schema")
        }
    }

}
