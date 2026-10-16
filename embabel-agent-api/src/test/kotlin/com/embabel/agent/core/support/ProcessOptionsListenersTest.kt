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
import com.embabel.agent.api.event.AgentProcessFailedEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent
import com.embabel.agent.api.event.AgentProcessStuckEvent
import com.embabel.agent.api.event.AgentProcessTerminatedEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.api.event.GoalAchievedEvent
import com.embabel.agent.api.event.ProcessKilledEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.ActionStatusCode
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
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

    private fun assertReceived(process: AgentProcess, vararg types: KClass<out AgentProcessEvent>) {
        val events = processListener.processEvents.filter { it.processId == process.id }
        val received = events.map { it::class.simpleName }.distinct()
        types.forEach { type ->
            assertTrue(
                events.any { type.isInstance(it) },
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
                process,
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

            assertReceived(process, AgentProcessWaitingEvent::class, AgentProcessTerminatedEvent::class)
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

            assertReceived(process, AgentProcessStuckEvent::class)
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

            assertReceived(process, ProcessKilledEvent::class)
            assertSameEventsAsPlatform(process)
        }
    }

    @Nested
    inner class Failed {

        @Test
        fun `receives failed event`() {
            val wizard = evenMoreEvilWizard()
            val failingWizard = wizard.copy(actions = wizard.actions.map { FailingAction(it) })
            val process = createProcess(failingWizard, mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))
            process.run()
            assertEquals(AgentProcessStatusCode.FAILED, process.status)

            assertReceived(process, AgentProcessFailedEvent::class)
            assertSameEventsAsPlatform(process)
        }
    }

    /**
     * Child processes inherit the parent's [ProcessOptions], so its listeners too.
     */
    @Nested
    inner class ChildProcess {

        @Test
        fun `receives the child's events`() {
            val parent = createProcess(DslWaitingAgent, mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))
            val child = platform.createChildProcess(evenMoreEvilWizard(), parent)
            child.run()
            assertEquals(AgentProcessStatusCode.COMPLETED, child.status)

            assertReceived(child, AgentProcessCreationEvent::class, AgentProcessCompletedEvent::class)
            assertSameEventsAsPlatform(child)
        }

        @Test
        fun `receives the child's killed event when the parent is killed`() {
            val parent = createProcess(DslWaitingAgent, mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))
            val child = platform.createChildProcess(evenMoreEvilWizard(), parent)

            platform.killAgentProcess(parent.id)
            assertEquals(AgentProcessStatusCode.KILLED, child.status)

            assertReceived(child, ProcessKilledEvent::class)
            assertSameEventsAsPlatform(child)
        }

        @Test
        fun `does not receive a killed event for a child that already finished`() {
            val parent = createProcess(DslWaitingAgent, mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")))
            val child = platform.createChildProcess(evenMoreEvilWizard(), parent)
            child.run()
            assertEquals(AgentProcessStatusCode.COMPLETED, child.status)

            platform.killAgentProcess(parent.id)

            // kill() still marks a finished child KILLED; this predates #2126 and is unchanged here
            assertEquals(AgentProcessStatusCode.KILLED, child.status)
            assertTrue(
                processListener.processEvents.none { it is ProcessKilledEvent && it.processId == child.id },
                "A child that already completed must not report being killed",
            )
            assertSameEventsAsPlatform(child)
        }
    }

    /**
     * Action that always reports failure, to drive a process to [AgentProcessStatusCode.FAILED].
     */
    private class FailingAction(delegate: Action) : Action by delegate {
        override fun execute(processContext: ProcessContext): ActionStatus =
            ActionStatus(Duration.ZERO, ActionStatusCode.FAILED)
    }
}
