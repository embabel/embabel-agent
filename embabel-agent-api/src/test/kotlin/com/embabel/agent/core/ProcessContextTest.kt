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
package com.embabel.agent.core

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.event.AgentProcessFinishedEvent
import com.embabel.agent.testing.common.EventSavingAgenticEventListener
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentProcessRunning
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProcessContextTest {

    @Test
    fun `no additional listeners`() {
        val ese = EventSavingAgenticEventListener()
        val platformServices = dummyPlatformServices(eventListener = ese)
        val agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard(), platformServices)
        val processContext = ProcessContext(
            ProcessOptions(),
            agentProcess = agentProcess,
            platformServices = platformServices,
        )
        assertEquals(0, ese.processEvents.size)
        processContext.onProcessEvent(
            AgentProcessFinishedEvent(
                agentProcess = agentProcess
            )
        )
        assertEquals(1, ese.processEvents.size)
    }

    @Test
    fun `additional listener`() {
        val ese = EventSavingAgenticEventListener()
        val platformServices = dummyPlatformServices(eventListener = ese)
        val agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard(), platformServices)
        val localListener = EventSavingAgenticEventListener()
        val processContext = ProcessContext(
            ProcessOptions(listeners = listOf(localListener)),
            agentProcess = agentProcess,
            platformServices = platformServices,
        )
        assertEquals(0, ese.processEvents.size)
        processContext.onProcessEvent(
            AgentProcessFinishedEvent(
                agentProcess = agentProcess
            )
        )
        assertEquals(1, ese.processEvents.size)
        assertEquals(1, localListener.processEvents.size)
    }

}
