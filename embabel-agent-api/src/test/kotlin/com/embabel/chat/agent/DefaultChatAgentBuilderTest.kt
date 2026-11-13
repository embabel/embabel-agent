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

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.DummyObjectCreatingLlmOperations
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.agent.test.integration.RandomRanker
import com.embabel.agent.test.integration.forAutonomyTesting
import com.embabel.agent.tools.agent.GoalTool
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.ai.model.LlmOptions
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    @Nested
    inner class GoalToolEvents {

        @Test
        fun `reach the chat listeners and the platform listener once`() {
            val platformListener = EventSavingAgenticEventListener()
            val chatListener = EventSavingAgenticEventListener()
            val agentPlatform = dummyAgentPlatform(listener = platformListener, llmOperations = GoalToolCallingLlm())
            val wizard = evenMoreEvilWizard()
            agentPlatform.deploy(wizard.copy(actions = wizard.actions.map { ToolCallingAction(it) }))
            val autonomy = Autonomy(agentPlatform, RandomRanker(), forAutonomyTesting())

            val chatProcess = agentPlatform.runAgentFrom(
                agent = DefaultChatAgentBuilder(autonomy, llm = LlmOptions()).build(),
                processOptions = ProcessOptions(listeners = listOf(chatListener)),
                bindings = mapOf("conversation" to InMemoryConversation(listOf(UserMessage("Turn Rod into a frog")))),
            )

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

            fun EventSavingAgenticEventListener.goalProcessCounts() =
                processEvents.filter { it.processId != chatProcess.id }.groupingBy { it::class.simpleName }.eachCount()
            assertEquals(
                platformListener.goalProcessCounts(),
                chatListener.goalProcessCounts(),
                "The chat's ProcessOptions listener must get the goal sub-process events as often as the platform listener",
            )

            val goalToolCalls = goalProcessEvents.count { it is ToolCallResponseEvent && it.request.tool == TOOL_NAME }
            assertTrue(goalToolCalls > 0, "The goal sub-process should have called $TOOL_NAME")
            assertEquals(
                goalToolCalls,
                chatProcess.toolsStats.toolsStats[TOOL_NAME]?.calls,
                "The chat's tool stats must count the tools called by the goal sub-process",
            )
        }
    }

    /**
     * Action that reports a tool call, as the tool decorator does when an LLM calls a tool.
     */
    private class ToolCallingAction(private val delegate: Action) : Action by delegate {
        override fun execute(processContext: ProcessContext): ActionStatus {
            val request = ToolCallRequestEvent(
                agentProcess = processContext.agentProcess,
                action = delegate,
                tool = TOOL_NAME,
                toolGroupMetadata = null,
                toolInput = "{}",
                llmOptions = LlmOptions(),
            )
            processContext.onProcessEvent(request)
            processContext.onProcessEvent(request.responseEvent(Result.success("done"), Duration.ZERO))
            return delegate.execute(processContext)
        }
    }

    /**
     * Fake LLM that calls the goal tool once, as a real model would.
     */
    private class GoalToolCallingLlm : DummyObjectCreatingLlmOperations(listOf("lorem")) {

        private val called = AtomicBoolean(false)

        override fun <O> doTransform(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): O {
            // Only the chat's LLM call offers goal tools. A real model would decide to call one;
            // here we call it directly. The goal sub-process also calls this LLM, so `called`
            // makes sure the goal tool runs only once.
            val goalTool = interaction.tools.firstOrNull { it is GoalTool<*> }
            if (goalTool != null && called.compareAndSet(false, true)) {
                goalTool.call("""{"content": "Rod"}""")
            }
            // Then answer like the dummy LLM: a random instance of the expected type
            return super.doTransform(messages, interaction, outputClass, llmRequestEvent)
        }
    }

    private companion object {
        const val TOOL_NAME = "frogTool"
    }
}
