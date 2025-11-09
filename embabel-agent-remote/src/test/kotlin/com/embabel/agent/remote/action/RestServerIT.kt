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
package com.embabel.agent.remote.action

import com.embabel.agent.core.ProcessContext
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentPlatform
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.http.converter.ResourceHttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter
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
        val objectMapper = jacksonObjectMapper()
        val restClient = RestServer.createRestClient(objectMapper)
        val restServer = RestServer(
            registration,
            restClient,
            objectMapper,
        )
        val agentScope = restServer.agentScope(agentPlatform)
        assertTrue(agentScope.actions.isNotEmpty(), "Should have had agents")
    }

    @Test
    fun testInvokeAction() {
        val agentPlatform = dummyAgentPlatform()
        val registration = RestServerRegistration(
            baseUrl = "http://localhost:8000",
            name = "python",
            description = "python actions",
        )
        val objectMapper = jacksonObjectMapper()
        val restClient = RestServer.createRestClient(objectMapper)
        val restServer = RestServer(
            registration,
            restClient,
            objectMapper,
        )
        val agentScope = restServer.agentScope(agentPlatform)
        assertTrue(agentScope.actions.isNotEmpty(), "Should have had agents")
        val greet = agentScope.actions.first { it.name == "greet" }
        val pc = mockk<ProcessContext>(relaxed = true)
        every { pc.getValue("input", "GreetingInput") } returns mapOf(
            "name" to "Bob", "language" to "en"
        )

        greet.execute(pc)

        // Test passes if no exception is thrown
    }

}
