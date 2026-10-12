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
package com.embabel.agent.core.support

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.ActionExecutionResultEvent
import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessCreationEvent
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ProcessOptions.listeners] are documented to receive events from their process.
 * They must see the same process events as the platform listener.
 * See https://github.com/embabel/embabel-agent/issues/2126
 */
class ProcessOptionsListenersTest {

    @Test
    fun `process options listeners receive the same process events as the platform listener`() {
        val platformListener = EventSavingAgenticEventListener()
        val processListener = EventSavingAgenticEventListener()
        val platform = IntegrationTestUtils.dummyAgentPlatform(listener = platformListener)

        val process = platform.createAgentProcess(
            evenMoreEvilWizard(),
            ProcessOptions(listeners = listOf(processListener)),
            mapOf("it" to UserInput("Rod")),
        )
        process.run()
        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

        val platformEventTypes = platformListener.processEvents.map { it::class.simpleName }.toSet()
        val processEventTypes = processListener.processEvents.map { it::class.simpleName }.toSet()
        assertEquals(
            emptySet<String>(),
            platformEventTypes - processEventTypes,
            "Event types received by the platform listener but missing for the ProcessOptions listener",
        )
    }

    @Test
    fun `process options listeners receive lifecycle events`() {
        val processListener = EventSavingAgenticEventListener()
        val platform = IntegrationTestUtils.dummyAgentPlatform()

        val process = platform.createAgentProcess(
            evenMoreEvilWizard(),
            ProcessOptions(listeners = listOf(processListener)),
            mapOf("it" to UserInput("Rod")),
        )
        process.run()
        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

        val events = processListener.processEvents
        listOf(
            AgentProcessCreationEvent::class,
            AgentProcessReadyToPlanEvent::class,
            AgentProcessPlanFormulatedEvent::class,
            ActionExecutionStartEvent::class,
            ActionExecutionResultEvent::class,
            GoalAchievedEvent::class,
            AgentProcessCompletedEvent::class,
        ).forEach { type ->
            assertTrue(
                events.any { type.isInstance(it) },
                "ProcessOptions listener did not receive ${type.simpleName}. Received: ${
                    events.map { it::class.simpleName }.distinct()
                }",
            )
        }
    }
}
