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
package com.embabel.agent.api.event.observation

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.ToolLoopStartEvent
import com.embabel.agent.core.ActionStatusCode
import com.embabel.agent.core.AgentProcess
import com.embabel.chat.Message
import com.embabel.plan.Action
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies each `*ObservationContext` renders a readable [toString] of its meaningful fields, so the
 * raw context is debuggable in logs and the debugger before the convention extracts its KeyValues.
 */
class ObservationContextToStringTest {

    @Test
    fun `AgentObservationContext renders the process id`() {
        val process = mockk<AgentProcess> { every { id } returns "p-1" }
        assertEquals(
            "AgentObservationContext(processId=p-1)",
            AgentObservationContext(process).toString(),
        )
    }

    @Test
    fun `ActionObservationContext renders process id, action name and status`() {
        val process = mockk<AgentProcess> { every { id } returns "p-1" }
        val action = mockk<Action> { every { name } returns "myAction" }
        val context = ActionObservationContext(process, action).apply { statusCode = ActionStatusCode.SUCCEEDED }
        assertEquals(
            "ActionObservationContext(processId=p-1, action=myAction, statusCode=SUCCEEDED)",
            context.toString(),
        )
    }

    @Test
    fun `ActionObservationContext renders a null status before the action runs`() {
        val process = mockk<AgentProcess> { every { id } returns "p-1" }
        val action = mockk<Action> { every { name } returns "myAction" }
        assertEquals(
            "ActionObservationContext(processId=p-1, action=myAction, statusCode=null)",
            ActionObservationContext(process, action).toString(),
        )
    }

    @Test
    fun `ToolLoopObservationContext renders the message count and output`() {
        val context = ToolLoopObservationContext(
            mockk<ToolLoopStartEvent>(),
            listOf(mockk<Message>(), mockk<Message>()),
        ).apply { output = "answer" }
        assertEquals(
            "ToolLoopObservationContext(inputMessages=2, output=answer)",
            context.toString(),
        )
    }

    @Test
    fun `LlmObservationContext renders the request event`() {
        val requestEvent = mockk<LlmRequestEvent<*>>()
        every { requestEvent.toString() } returns "REQ"
        assertEquals(
            "LlmObservationContext(requestEvent=REQ)",
            LlmObservationContext(requestEvent).toString(),
        )
    }
}
