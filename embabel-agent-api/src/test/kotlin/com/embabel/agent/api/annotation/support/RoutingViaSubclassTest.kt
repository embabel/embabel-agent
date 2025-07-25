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