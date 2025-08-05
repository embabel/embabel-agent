/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.chat.agent.shell

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.GoalChoiceApprover
import com.embabel.agent.domain.library.Person
import com.embabel.agent.shell.config.ShellProperties
import com.embabel.chat.AssistantMessage
import com.embabel.chat.MessageSavingMessageListener
import com.embabel.chat.UserMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LastMessageIntentAgentPlatformChatSessionTest {

    data class LocalPerson(override val name: String) : Person

    @Test
    fun `should invoke chooseAndAccomplishGoal`() {
        val mockAutonomy = mockk<Autonomy>()
        every { mockAutonomy.agentPlatform } returns mockk()
        val intent = slot<Map<String, Any>>()
        val der = mockk<AgentProcessExecution>()
        val output = LocalPerson("Gordon")
        every { der.output } returns output

        every {
            mockAutonomy.chooseAndAccomplishGoal(
                bindings = capture(intent),
                goalChoiceApprover = any(),
                agentScope = any(),
                processOptions = any(),
                goalSelectionOptions = any(),
            )
        } returns der
        val chatSession = LastMessageIntentAgentPlatformChatSession(
            mockAutonomy,
            GoalChoiceApprover.APPROVE_ALL,
            messageListener = {},
            terminalServices = mockk(),
            config = ShellProperties.ChatConfig(),
        )
        val userMessage = UserMessage("Hello, world!")
        val l = MessageSavingMessageListener()
        chatSession.respond(userMessage, l)
        assertEquals(1, l.messages().size)
        Assertions.assertTrue(l.messages()[0] is AssistantMessage)
        Assertions.assertTrue(
            l.messages()[0].content.contains(output.name),
            "Expected message to contain the name of the output person: got ${l.messages()[0].content}",
        )
    }

}
