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
package com.embabel.agent.event

import com.embabel.agent.api.event.ToolLoopCompletedEvent
import com.embabel.agent.api.event.ToolLoopStartEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolLoopEventTest {

    private val agentProcess = mockk<AgentProcess>(relaxed = true).also {
        every { it.id } returns "test-run-id"
    }

    private val action = mockk<Action>(relaxed = true).also {
        every { it.name } returns "TestAction"
        every { it.shortName() } returns "TestAction"
    }

    @Nested
    inner class `ToolLoopStartEvent creation` {

        @Test
        fun `should capture all properties`() {
            val event = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = action,
                toolNames = listOf("countWords", "countChars"),
                maxIterations = 10,
                interactionId = "interaction-1",
                outputClass = String::class.java,
            )
            assertEquals("test-run-id", event.processId)
            assertEquals(action, event.action)
            assertEquals(listOf("countWords", "countChars"), event.toolNames)
            assertEquals(10, event.maxIterations)
            assertEquals("interaction-1", event.interactionId)
            assertEquals(String::class.java, event.outputClass)
            assertNotNull(event.timestamp)
        }

        @Test
        fun `should allow null action`() {
            val event = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = null,
                toolNames = emptyList(),
                maxIterations = 5,
                interactionId = "interaction-2",
                outputClass = String::class.java,
            )
            assertEquals(null, event.action)
        }
    }

    @Nested
    inner class `ToolLoopCompletedEvent via completedEvent` {

        @Test
        fun `should create completed event with correct properties`() {
            val startEvent = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = action,
                toolNames = listOf("toolA"),
                maxIterations = 10,
                interactionId = "interaction-3",
                outputClass = String::class.java,
            )

            val completedEvent = startEvent.completedEvent(
                totalIterations = 3,
                replanRequested = false,
            )

            assertEquals("test-run-id", completedEvent.processId)
            assertEquals(action, completedEvent.action)
            assertEquals(listOf("toolA"), completedEvent.toolNames)
            assertEquals(10, completedEvent.maxIterations)
            assertEquals("interaction-3", completedEvent.interactionId)
            assertEquals(String::class.java, completedEvent.outputClass)
            assertEquals(3, completedEvent.totalIterations)
            assertFalse(completedEvent.replanRequested)
            assertNotNull(completedEvent.runningTime)
            assertTrue(completedEvent.runningTime.toMillis() >= 0)
        }

        @Test
        fun `should capture replan requested`() {
            val startEvent = ToolLoopStartEvent(
                agentProcess = agentProcess,
                action = null,
                toolNames = listOf("toolB", "toolC"),
                maxIterations = 5,
                interactionId = "interaction-4",
                outputClass = Any::class.java,
            )

            val completedEvent = startEvent.completedEvent(
                totalIterations = 2,
                replanRequested = true,
            )

            assertTrue(completedEvent.replanRequested)
            assertEquals(2, completedEvent.totalIterations)
        }
    }
}
