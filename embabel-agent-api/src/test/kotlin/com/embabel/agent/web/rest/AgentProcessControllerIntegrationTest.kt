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
import com.fasterxml.jackson.databind.ObjectMapper
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
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@EnableAutoConfiguration
class AgentProcessControllerIntegrationTest(
    @Autowired
    private val mockMvc: MockMvc,
    @Autowired
    private val objectMapper: ObjectMapper,
    @Autowired
    private val agentPlatform: AgentPlatform,
) {

    @Test
    fun `should return no such status`() {
        mockMvc.get("/api/v1/process/i-made-this-up")
            .andExpect {
                status().isNotFound()
            }.andReturn()
    }


    @Test
    fun `should return process status`() {
        val agentProcess = agentPlatform.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
        val result = mockMvc.get("/api/v1/process/${agentProcess.id}")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        val retrievedProcess = objectMapper.readValue(content, AgentProcessStatusReport::class.java)
        assertEquals(agentProcess.id, retrievedProcess.id)
    }

    @Test
    fun `kill no such process`() {
        val result = mockMvc.delete("/api/v1/process/i-made-this-up")
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
        val retrievedProcess = objectMapper.readValue(content, AgentProcessStatusReport::class.java)
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
