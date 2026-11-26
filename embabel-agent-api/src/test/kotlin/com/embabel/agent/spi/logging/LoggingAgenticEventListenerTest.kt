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
package com.embabel.agent.spi.logging

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.ModelCallEvent
import com.embabel.agent.api.event.ToolLoopCompletedEvent
import com.embabel.agent.api.event.ToolLoopStartEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Verbosity
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmMetadata
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.slf4j.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private class TestableLoggingAgenticEventListener : LoggingAgenticEventListener() {
    public override fun getToolLoopStartMessage(e: ToolLoopStartEvent) = super.getToolLoopStartMessage(e)
    public override fun getToolLoopCompletedMessage(e: ToolLoopCompletedEvent) = super.getToolLoopCompletedMessage(e)
    public override fun getModelCallEventMessage(e: ModelCallEvent) = super.getModelCallEventMessage(e)
}

class LoggingAgenticEventListenerTest {

    private val listener = TestableLoggingAgenticEventListener()

    private val agentProcess = mockk<AgentProcess>(relaxed = true).also {
        every { it.id } returns "test-process-id"
    }

    private val action = mockk<Action>(relaxed = true).also {
        every { it.name } returns "com.example.MyAgent.doSomething"
        every { it.shortName() } returns "doSomething"
    }

    @Nested
    inner class ToolLoopStartMessageTests {

        @Test
        fun `uses action shortName when action is present`() {
            val event = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = action,
                toolNames = listOf("search"),
                maxIterations = 10,
                interactionId = "i1",
                outputClass = String::class.java,
            )
            val message = listener.getToolLoopStartMessage(event)
            assertTrue(message.contains("doSomething"), "Expected action shortName in: $message")
            assertFalse(message.contains("null"), "Expected no 'null' in: $message")
        }

        @Test
        fun `uses outputClass simpleName when action is null`() {
            val event = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = null,
                toolNames = listOf("search"),
                maxIterations = 10,
                interactionId = "i1",
                outputClass = String::class.java,
            )
            val message = listener.getToolLoopStartMessage(event)
            assertTrue(message.contains("String"), "Expected output class name in: $message")
            assertFalse(message.contains("null"), "Expected no 'null' in: $message")
        }
    }

    @Nested
    inner class ToolLoopCompletedMessageTests {

        @Test
        fun `uses action shortName when action is present`() {
            val startEvent = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = action,
                toolNames = listOf("search"),
                maxIterations = 10,
                interactionId = "i1",
                outputClass = String::class.java,
            )
            val event = startEvent.completedEvent(totalIterations = 3, replanRequested = false)
            val message = listener.getToolLoopCompletedMessage(event)
            assertTrue(message.contains("doSomething"), "Expected action shortName in: $message")
            assertFalse(message.contains("null"), "Expected no 'null' in: $message")
        }

        @Test
        fun `uses outputClass simpleName when action is null`() {
            val startEvent = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = null,
                toolNames = listOf("search"),
                maxIterations = 10,
                interactionId = "i1",
                outputClass = String::class.java,
            )
            val event = startEvent.completedEvent(totalIterations = 1, replanRequested = false)
            val message = listener.getToolLoopCompletedMessage(event)
            assertTrue(message.contains("String"), "Expected output class name in: $message")
            assertFalse(message.contains("null"), "Expected no 'null' in: $message")
        }
    }

    @Nested
    inner class ModelCallEventMessageTests {

        private fun requestEvent(agentProcess: AgentProcess) = LlmRequestEvent(
            agentProcess = agentProcess,
            action = action,
            outputClass = String::class.java,
            interaction = LlmInteraction(id = InteractionId("i1")),
            llmMetadata = mockk<LlmMetadata>(relaxed = true).also { every { it.name } returns "local-model" },
            messages = emptyList(),
        )

        private fun loggedModelCalls(showPrompts: Boolean): Int {
            val logged = mutableListOf<String>()
            val logger = mockk<Logger>(relaxed = true).also { every { it.info(capture(logged)) } just Runs }
            val process = mockk<AgentProcess>(relaxed = true).also {
                every { it.processContext.processOptions.verbosity } returns Verbosity(showPrompts = showPrompts)
            }
            LoggingAgenticEventListener(logger = logger).onProcessEvent(
                ModelCallEvent(request = requestEvent(process), messages = listOf(UserMessage("hi")), tools = emptyList())
            )
            return logged.count { it.contains("model call") }
        }

        @Test
        fun `logs the event with showPrompts`() {
            assertEquals(1, loggedModelCalls(showPrompts = true))
        }

        @Test
        fun `does not log the event without showPrompts`() {
            assertEquals(0, loggedModelCalls(showPrompts = false))
        }

        @Test
        fun `shows the model, the messages sent and the tools`() {
            val request = LlmRequestEvent(
                agentProcess = agentProcess,
                action = action,
                outputClass = String::class.java,
                interaction = LlmInteraction(id = InteractionId("i1")),
                llmMetadata = mockk<LlmMetadata>(relaxed = true).also { every { it.name } returns "local-model" },
                messages = emptyList(),
            )
            val tool = mockk<Tool>().also {
                every { it.definition.name } returns "search"
                every { it.definition.description } returns "Search the web"
            }
            val event = ModelCallEvent(
                request = request,
                messages = listOf(SystemMessage("Be brief"), UserMessage("Name a dog")),
                tools = listOf(tool),
            )

            val message = listener.getModelCallEventMessage(event)

            listOf("local-model", "Be brief", "Name a dog", "search", "i1").forEach {
                assertTrue(message.contains(it), "Expected '$it' in: $message")
            }
        }
    }
}
