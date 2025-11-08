package com.embabel.agent.remote.action

import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentPlatform
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class RestServerIT {

    @Test
    fun testConnection() {
        val agentPlatform = dummyAgentPlatform()
        val registration = RestServerRegistration(
            baseUrl = "http://localhost:8000",
            name = "python",
            description = "python actions",
        )
        val restServer = RestServer(
            registration,
            RestClient.builder().build(),
        )
        val agentScope = restServer.agentScope(agentPlatform)
        assertTrue(agentScope.actions.isNotEmpty(), "Should have had agents")
    }

}