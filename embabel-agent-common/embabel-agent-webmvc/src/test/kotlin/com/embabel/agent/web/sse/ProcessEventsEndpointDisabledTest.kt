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
package com.embabel.agent.web.sse

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.dsl.evenMoreEvilWizard
import com.embabel.common.test.ai.config.FakeAiConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertFalse

@SpringBootTest(properties = ["embabel.agent.platform.rest.process-events-enabled=false"])
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@Import(FakeAiConfiguration::class)
@EnableAutoConfiguration
class ProcessEventsEndpointDisabledTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val agentPlatform: AgentPlatform,
    @param:Autowired private val applicationContext: ApplicationContext,
) {
    @Test
    fun `SSE events endpoint is disabled`() {
        val process = agentPlatform.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
        mockMvc.get("/events/process/${process.id}")
            .andExpect { status().isNotFound() }
    }

    @Test
    fun `SSEController bean is not registered`() {
        assertFalse(applicationContext.containsBean("SSEController"))
        assertFalse(applicationContext.getBeansOfType(SSEController::class.java).isNotEmpty())
    }

    @Test
    fun `GET process status remains enabled`() {
        val process = agentPlatform.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
        mockMvc.get("/api/v1/process/${process.id}")
            .andExpect { status().isOk() }
    }
}
