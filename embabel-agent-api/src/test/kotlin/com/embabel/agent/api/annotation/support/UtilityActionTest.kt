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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.plan.utility.UtilityPlanner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

class UtilityActionTest {

    @Test
    fun `planner type is correct`() {
        val reader = AgentMetadataReader()
        val metadata =
            reader.createAgentMetadata(
                Utility1()
            )
        assertNotNull(metadata)
        assertEquals(2, metadata!!.actions.size)

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agent = metadata as CoreAgent
        val agentProcess =
            ap.createAgentProcess(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY),
                emptyMap(),
            )
        assertTrue(
            agentProcess.planner is UtilityPlanner,
            "Planner must be UtilityPlanner: have ${agentProcess.planner}",
        )
    }

    @Test
    fun `synthetic goal is created`() {
        val reader = AgentMetadataReader()
        val metadata =
            reader.createAgentMetadata(
                Utility1()
            )
        assertNotNull(metadata)
        val goal = metadata!!.goals.singleOrNull()
        assertNotNull(goal, "Must have a goal")
    }

    @Test
    fun `invoke two actions`() {
        val reader = AgentMetadataReader()
        val metadata =
            reader.createAgentMetadata(
                Utility1()
            )
        assertNotNull(metadata)
        assertEquals(2, metadata!!.actions.size)

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agent = metadata as CoreAgent
        val agentProcess =
            ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY),
                emptyMap(),
            )
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
        assertTrue(
            agentProcess.objects.any { it == Frog("Kermit") },
            "Should have a frog: blackboard=${agentProcess.objects}"
        )
        assertTrue(
            agentProcess.objects.any { it == PersonWithReverseTool("Kermit") },
            "Should have a person: blackboard=${agentProcess.objects}",
        )
    }
}
