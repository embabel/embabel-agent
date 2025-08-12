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
import com.embabel.agent.core.*
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@EnableAutoConfiguration
class PlatformInfoControllerIntegrationTest(
    @param:Autowired
    private val mockMvc: MockMvc,
    @param:Autowired
    private val objectMapper: ObjectMapper,
    @param:Autowired
    private val agentPlatform: AgentPlatform,
) {

    @Test
    fun `should return agents`() {
        agentPlatform.deploy(evenMoreEvilWizard())
        val result = mockMvc.get("/api/v1/platform-info/agents")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        val retrievedAgents = objectMapper.readValue(content, object : TypeReference<List<AgentMetadata>>() {})
        assertTrue(retrievedAgents.isNotEmpty(), "Must have some agents in $content")
    }

    @Test
    fun `should return goals`() {
        agentPlatform.deploy(evenMoreEvilWizard())
        val result = mockMvc.get("/api/v1/platform-info/goals")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        val retrievedGoals = objectMapper.readValue(content, object : TypeReference<List<Goal>>() {})
        assertTrue(retrievedGoals.isNotEmpty(), "Must have some goals in $content")
    }

    @Test
    fun `should return actions`() {
        agentPlatform.deploy(evenMoreEvilWizard())
        val result = mockMvc.get("/api/v1/platform-info/actions")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        val retrievedActions = objectMapper.readValue(content, object : TypeReference<List<ActionMetadata>>() {})
        assertTrue(retrievedActions.isNotEmpty(), "Must have some actions in $content")
    }

    @Test
    fun `should return models`() {
        val result = mockMvc.get("/api/v1/platform-info/models")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        assertTrue(content.contains("LLM"))
        assertTrue(content.contains("EMBEDDING"))
    }

    @Test
    fun `should return tool groups`() {
        val result = mockMvc.get("/api/v1/platform-info/tool-groups")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        val retrievedActions = objectMapper.readValue(content, object : TypeReference<List<ToolGroupMetadata>>() {})
        assertTrue(retrievedActions.isNotEmpty(), "Must have some tool groups in $content")
    }


    @Test
    fun `should return PlatformInfo`() {
        agentPlatform.deploy(evenMoreEvilWizard())
        val result = mockMvc.get("/api/v1/platform-info")
            .andExpect {
                status().isOk()
            }.andReturn()
        val content = result.response.contentAsString
        val platformInfo = objectMapper.readValue(content, PlatformInfoSummary::class.java)
        assertEquals(1, platformInfo.agentCount)
        assertTrue(platformInfo.actionCount > 1)
//        assertTrue(platformInfo.goalCount > 1)
        assertTrue(platformInfo.domainTypes.isNotEmpty())
        assertTrue(platformInfo.toolGroups.isNotEmpty(), "Must have some tool groups")
        assertTrue(platformInfo.models.isNotEmpty(), "Must have some models in $content")
    }
}
