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

import com.embabel.agent.api.event.ToolLoopCompletedEvent
import com.embabel.agent.api.event.ToolLoopStartEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private class TestableLoggingAgenticEventListener : LoggingAgenticEventListener() {
    public override fun getToolLoopStartMessage(e: ToolLoopStartEvent) = super.getToolLoopStartMessage(e)
    public override fun getToolLoopCompletedMessage(e: ToolLoopCompletedEvent) = super.getToolLoopCompletedMessage(e)
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
}
