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

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.testing.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Test for routing via subclassing.
 */
@Disabled("Known issue: see Issue #631")
class RoutingViaSubclassTest {

    @Test
    fun `billing routing`() {
        checkRouting("billing")
    }

    @Test
    fun `sales routing`() {
        checkRouting("sales")
    }

    @Test
    fun `service routing`() {
        checkRouting("service")
    }

    @Test
    fun noSuchRouting() {
        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(IntentReceptionAgent()) as Agent

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess =
            ap.runAgentFrom(agent, ProcessOptions(), mapOf("it" to UserInput("meaningless-routing")))
        assertEquals(AgentProcessStatusCode.STUCK, agentProcess.status)
    }

    private fun checkRouting(routing: String) {
        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(IntentReceptionAgent()) as Agent

        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val agentProcess =
            ap.runAgentFrom(agent, ProcessOptions(), mapOf("it" to UserInput(routing)))
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)
        assertEquals(
            IntentClassificationSuccess(routing), agentProcess.lastResult(),
            "Should have detected $routing intent"
        )
    }


}
