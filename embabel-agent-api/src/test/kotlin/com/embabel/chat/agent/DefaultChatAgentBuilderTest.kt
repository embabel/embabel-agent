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
package com.embabel.chat.agent

import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.test.integration.RandomRanker
import com.embabel.agent.test.integration.forAutonomyTesting
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

class DefaultChatAgentBuilderTest {

    @Test
    fun `emits one message`() {
        val agentPlatform = dummyAgentPlatform()
        val cab = DefaultChatAgentBuilder(
            mockk(),
            llm = LlmOptions(),
            persona = MARVIN,
        )
        val chatAgent = cab.build()
        val m = UserMessage("Hello")
        val conversation = InMemoryConversation(listOf(m))
        val agentProcess = agentPlatform.runAgentFrom(
            agent = chatAgent,
            processOptions = ProcessOptions(),
            bindings = mapOf(
                "conversation" to conversation,
            )
        )
        assertEquals(
            AgentProcessStatusCode.STUCK, agentProcess.status,
            "It's OK to be stuck waiting for another user message"
        )
    }

    /**
     * Goal tools run a sub-process whose events are forwarded to the chat process.
     * The chat's ProcessOptions listeners must see them, and the platform listener
     * must not get them twice. See https://github.com/embabel/embabel-agent/issues/2126
     */
    @Test
    fun `goal tool events reach the chat listeners once`() {
        val platformListener = EventSavingAgenticEventListener()
        val chatListener = EventSavingAgenticEventListener()
        val agentPlatform = dummyAgentPlatform(listener = platformListener)
        agentPlatform.deploy(evenMoreEvilWizard())
        val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

        val chatProcess = agentPlatform.createAgentProcess(
            evenMoreEvilWizard(),
            ProcessOptions(listeners = listOf(chatListener)),
            emptyMap(),
        )
        val context = mockk<TransformationActionContext<*, *>>()
        every { context.processContext } returns chatProcess.processContext
        every { context.agentPlatform() } returns agentPlatform
        // Like real contexts, which delegate AgenticEventListener to their processContext
        every { context.onProcessEvent(any()) } answers { chatProcess.processContext.onProcessEvent(firstArg()) }

        val toolGroup = DefaultChatAgentBuilder(autonomy, llm = LlmOptions()).buildToolGroup(context)
        toolGroup.tools.single().call("""{"content": "Rod"}""")

        val goalProcessEvents = platformListener.processEvents.filter { it.processId != chatProcess.id }
        assertTrue(
            goalProcessEvents.any { it is AgentProcessCompletedEvent },
            "The goal sub-process should have completed",
        )
        assertTrue(
            chatListener.processEvents.any { it is AgentProcessCompletedEvent && it.processId != chatProcess.id },
            "The chat's ProcessOptions listener should see the goal sub-process complete",
        )

        val deliveries = IdentityHashMap<AgentProcessEvent, Int>()
        goalProcessEvents.forEach { deliveries.merge(it, 1, Int::plus) }
        val duplicated = deliveries.filterValues { it > 1 }.map { (event, count) -> "${event::class.simpleName} x$count" }
        assertEquals(
            emptyList<String>(),
            duplicated,
            "Events delivered more than once to the platform listener",
        )
    }
}
