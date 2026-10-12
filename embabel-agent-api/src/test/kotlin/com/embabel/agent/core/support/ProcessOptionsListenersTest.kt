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
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent
import com.embabel.agent.api.event.AgentProcessStuckEvent
import com.embabel.agent.api.event.AgentProcessTerminatedEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.api.event.ProcessKilledEvent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * [ProcessOptions.listeners] are documented to receive events from their process.
 * They must get the same process events as the platform listener, whatever way the process ends.
 * See https://github.com/embabel/embabel-agent/issues/2126
 */
class ProcessOptionsListenersTest {

    private val platformListener = EventSavingAgenticEventListener()
    private val processListener = EventSavingAgenticEventListener()
    private val platform = IntegrationTestUtils.dummyAgentPlatform(listener = platformListener)

    private fun createProcess(agent: Agent, bindings: Map<String, Any>): AgentProcess =
        platform.createAgentProcess(agent, ProcessOptions(listeners = listOf(processListener)), bindings)

    private fun EventSavingAgenticEventListener.countsFor(process: AgentProcess): Map<String?, Int> =
        processEvents.filter { it.processId == process.id }.groupingBy { it::class.simpleName }.eachCount()

    private fun assertSameEventsAsPlatform(process: AgentProcess) {
        assertEquals(
            platformListener.countsFor(process),
            processListener.countsFor(process),
            "ProcessOptions listener must get the same events, as often, as the platform listener",
        )
    }

    private fun assertReceived(vararg types: KClass<out AgentProcessEvent>) {
        val received = processListener.processEvents.map { it::class.simpleName }.distinct()
        types.forEach { type ->
            assertTrue(
                processListener.processEvents.any { type.isInstance(it) },
                "ProcessOptions listener did not receive ${type.simpleName}. Received: $received",
            )
        }
    }

    @Nested
    inner class Completed {

        @Test
        fun `receives the same events as the platform listener`() {
            val process = createProcess(evenMoreEvilWizard(), mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))
            process.run()
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

            assertSameEventsAsPlatform(process)
        }

        @Test
        fun `receives lifecycle events`() {
            val process = createProcess(evenMoreEvilWizard(), mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))
            process.run()
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

            assertReceived(
                AgentProcessCreationEvent::class,
                AgentProcessReadyToPlanEvent::class,
                AgentProcessPlanFormulatedEvent::class,
                ActionExecutionStartEvent::class,
                ActionExecutionResultEvent::class,
                GoalAchievedEvent::class,
                AgentProcessCompletedEvent::class,
            )
        }
    }

    @Nested
    inner class WaitingThenTerminated {

        @Test
        fun `receives waiting and terminated events`() {
            val process = createProcess(DslWaitingAgent, mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))
            process.run()
            assertEquals(AgentProcessStatusCode.WAITING, process.status)

            process.terminateAgent("Stopped by test")
            assertEquals(AgentProcessStatusCode.TERMINATED, process.status)

            assertReceived(AgentProcessWaitingEvent::class, AgentProcessTerminatedEvent::class)
            assertSameEventsAsPlatform(process)
        }
    }

    @Nested
    inner class Stuck {

        @Test
        fun `receives stuck event`() {
            val process = createProcess(DslWaitingAgent, emptyMap())
            process.run()
            assertEquals(AgentProcessStatusCode.STUCK, process.status)

            assertReceived(AgentProcessStuckEvent::class)
            assertSameEventsAsPlatform(process)
        }
    }

    @Nested
    inner class Killed {

        @Test
        fun `receives killed event`() {
            val process = createProcess(evenMoreEvilWizard(), mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))

            platform.killAgentProcess(process.id)
            assertEquals(AgentProcessStatusCode.KILLED, process.status)

            assertReceived(ProcessKilledEvent::class)
            assertSameEventsAsPlatform(process)
        }
    }
}
