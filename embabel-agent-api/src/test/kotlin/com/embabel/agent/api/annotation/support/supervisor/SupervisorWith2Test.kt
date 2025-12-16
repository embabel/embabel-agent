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
package com.embabel.agent.api.annotation.support.supervisor

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

class SupervisorWith2Test {

    @Test
    fun `supervisor agent metadata is read correctly`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2())
        assertNotNull(metadata)
        assertTrue(metadata is CoreAgent)
        metadata as CoreAgent
        assertEquals(2, metadata.actions.size)
        assertEquals(1, metadata.goals.size)
    }

    @Test
    fun `supervisor agent runs and achieves goal`() {
        val reader = AgentMetadataReader()
        val metadata = reader.createAgentMetadata(SupervisorWith2()) as CoreAgent

        val ap = dummyAgentPlatform()
        val agentProcess = ap.runAgentFrom(
            metadata,
            ProcessOptions(plannerType = PlannerType.SUPERVISOR),
            mapOf("it" to UserInput("Kermit")),
        )
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
        assertEquals(Prince("Prince from Kermit"), agentProcess.lastResult())
    }
}
