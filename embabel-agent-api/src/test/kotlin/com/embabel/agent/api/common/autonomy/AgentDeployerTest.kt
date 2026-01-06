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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.api.dsl.EvilWizardAgent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.deployment.AgentDeployer
import com.embabel.agent.core.deployment.AgentScanningProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentDeployerTest {

    @Test
    fun `no agents`() {
        val agentDeployer = AgentDeployer(
            agents = emptyList(),
            agentPlatform = mockk(),
            properties = AgentScanningProperties(
                annotation = false,
                bean = true,
            ),
        )
        val allAgents = agentDeployer.agents
        assertTrue(allAgents.isEmpty(), "No agents")
    }

    @Test
    fun `no publication because disabled`() {
        val agentDeployer = AgentDeployer(
            agents = listOf(EvilWizardAgent),
            agentPlatform = mockk(),
            properties = AgentScanningProperties(
                annotation = false,
                bean = false,
            ),
        )
        val allAgents = agentDeployer.agents
        assertEquals(1, allAgents.size)
        // Should not have deployed
    }

    @Test
    fun publication() {
        val mockAgentPlatform = mockk<AgentPlatform>()
        every { mockAgentPlatform.deploy(any()) } returns mockAgentPlatform
        val agentDeployer = AgentDeployer(
            agents = listOf(EvilWizardAgent),
            agentPlatform = mockAgentPlatform,
            properties = AgentScanningProperties(
                annotation = false,
                bean = true,
            ),
        )
        val allAgents = agentDeployer.agents
        assertEquals(1, allAgents.size)
        // Should have deployed
        verify(exactly = 1) { mockAgentPlatform.deploy(EvilWizardAgent) }
    }

}
