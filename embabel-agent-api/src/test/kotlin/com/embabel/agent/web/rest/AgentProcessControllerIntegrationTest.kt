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
package com.embabel.agent.web.rest

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.AgentProcessStatusReport
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@EnableAutoConfiguration
class AgentProcessControllerIntegrationTest(
    @param:Autowired
    private val mockMvc: MockMvc,
    @param:Autowired
    private val objectMapper: ObjectMapper,
    @param:Autowired
    private val agentPlatform: AgentPlatform,
) {

    @Nested
    inner class GetStatus {

        @Test
        fun `no such process`() {
            mockMvc.get("/api/v1/process/i_made_this_up")
                .andExpect {
                    status().isNotFound()
                }
        }

        @Test
        fun `should find process not started`() {
            val process = agentPlatform.createAgentProcess(agent = evenMoreEvilWizard(), ProcessOptions(), emptyMap())
            val result = mockMvc.get("/api/v1/process/${process.id}")
                .andExpect {
                    status().isOk()
                }.andReturn()
            val content = result.response.contentAsString
            val status = objectMapper.readValue(content, AgentProcessStatus::class.java)
            assertEquals(AgentProcessStatusCode.NOT_STARTED, status.status)
            assertNull(status.result)
        }

        @Test
        fun `should return valid status endpoint`() {
            val process = agentPlatform.createAgentProcess(agent = evenMoreEvilWizard(), ProcessOptions(), emptyMap())
            val result = mockMvc.get("/api/v1/process/${process.id}")
                .andExpect {
                    status().isOk()
                }.andReturn()
            val content = result.response.contentAsString
            val status = objectMapper.readValue(content, AgentProcessStatus::class.java)
            val result2 = mockMvc.get(status.statusUrl)
                .andExpect {
                    status().isOk()
                }.andReturn()
            assertEquals(result.response.contentAsString, result2.response.contentAsString)
        }

        @Test
        fun `should return valid SSE endpoint`() {
            val process = agentPlatform.createAgentProcess(agent = evenMoreEvilWizard(), ProcessOptions(), emptyMap())
            val result = mockMvc.get("/api/v1/process/${process.id}")
                .andExpect {
                    status().isOk()
                }.andReturn()
            val content = result.response.contentAsString
            val status = objectMapper.readValue(content, AgentProcessStatus::class.java)
            mockMvc.get(status.sseUrl)
                .andExpect {
                    status().isOk()
                }.andReturn()
        }

        @Test
        fun `should find process finished`() {
            val process = agentPlatform.createAgentProcess(
                agent = evenMoreEvilWizard(), ProcessOptions(), mapOf(
                    "it" to UserInput("Hamish will be consumed!")
                )
            )
            process.run()
            val result = mockMvc.get("/api/v1/process/${process.id}")
                .andExpect {
                    status().isOk()
                }.andReturn()
            val content = result.response.contentAsString
            val status = objectMapper.readValue(content, AgentProcessStatus::class.java)
            assertEquals(AgentProcessStatusCode.COMPLETED, status.status)
            assertNotNull(status.result)
            val snakeMeal = status.result
            assertTrue(snakeMeal.toString().contains("Hamish"), "Unexpected result: $snakeMeal")
        }
    }

    @Test
    fun `kill no such process`() {
        mockMvc.delete("/api/v1/process/i-made-this-up")
            .andExpect {
                status().isNotFound()
            }.andReturn()
    }

    @Test
    fun `should kill existing process`() {
        val agentProcess = agentPlatform.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
        val result = mockMvc.delete("/api/v1/process/${agentProcess.id}")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        val retrievedProcess = objectMapper.readValue(content, AgentProcessStatus::class.java)
        assertEquals(agentProcess.id, retrievedProcess.id)
        assertEquals(AgentProcessStatusCode.KILLED, retrievedProcess.status)

        val result2 = mockMvc.get("/api/v1/process/${agentProcess.id}")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content2 = result2.response.contentAsString
        val retrievedProcess2 = objectMapper.readValue(content2, AgentProcessStatusReport::class.java)
        assertEquals(agentProcess.id, retrievedProcess2.id)
        assertEquals(AgentProcessStatusCode.KILLED, retrievedProcess2.status)
    }

}
