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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.agent.test.integration.RandomRanker
import com.embabel.agent.test.integration.forAutonomyTesting
import com.embabel.chat.agent.forwardToProcessOptionsListeners
import com.embabel.common.core.types.NamedAndDescribed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A chat agent forwards the events of its goal sub-processes to its own process context,
 * so that its [ProcessOptions.listeners] see them. That forwarding must not make the
 * platform listener receive the same event twice.
 * See https://github.com/embabel/embabel-agent/issues/2126
 */
class GoalToolEventForwardingTest {

    private val textCommunicator = object : TextCommunicator {
        override fun communicateAwaitable(goal: NamedAndDescribed, pwe: ProcessWaitingException) = "awaiting"
        override fun communicateResult(agentProcessExecution: AgentProcessExecution) = "result"
    }

    @Test
    fun `platform listener receives each goal sub-process event once`() {
        val platformListener = EventSavingAgenticEventListener()
        val parentListener = EventSavingAgenticEventListener()
        val agentPlatform = IntegrationTestUtils.dummyAgentPlatform(listener = platformListener)
        agentPlatform.deploy(evenMoreEvilWizard())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val parent = agentPlatform.createAgentProcess(
            evenMoreEvilWizard(),
            ProcessOptions(listeners = listOf(parentListener)),
            emptyMap(),
        )
        val forwardToParent = forwardToProcessOptionsListeners(parent.processContext)
        val goalTool = GoalTool(
            autonomy = autonomy,
            textCommunicator = textCommunicator,
            name = "wizard",
            goal = agentPlatform.goals.single(),
            inputType = UserInput::class.java,
            listeners = listOf(forwardToParent),
        )

        goalTool.call("""{"content": "Rod"}""")

        val subProcessEvents = platformListener.processEvents.filter { it.processId != parent.id }
        assertTrue(
            subProcessEvents.any { it is AgentProcessCompletedEvent },
            "The goal sub-process should have completed",
        )
        assertTrue(
            parentListener.processEvents.any { it is AgentProcessCompletedEvent && it.processId != parent.id },
            "The parent's ProcessOptions listener should see the goal sub-process complete",
        )

        val duplicated = subProcessEvents
            .groupBy { System.identityHashCode(it) }
            .values
            .filter { it.size > 1 }
            .map { "${it.first()::class.simpleName} x${it.size}" }
        assertEquals(
            emptyList<String>(),
            duplicated,
            "Events delivered more than once to the platform listener",
        )
    }
}
