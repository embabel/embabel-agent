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

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.hitl.ConfirmationRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultProcessCallbackToolsTest {

    data class Payment(val amount: Int)

    private val agentPlatform = mockk<AgentPlatform>()
    private val autonomy = mockk<Autonomy> {
        every { agentPlatform } returns this@DefaultProcessCallbackToolsTest.agentPlatform
    }
    private val textCommunicator = mockk<TextCommunicator>()
    private val tools = DefaultProcessCallbackTools(autonomy, textCommunicator)

    private fun processWaitingOn(request: ConfirmationRequest<*>): AgentProcess {
        val agentProcess = mockk<AgentProcess>(relaxed = true)
        every { agentProcess.id } returns "p1"
        every { agentProcess.lastResult() } returns request
        every { agentProcess.status } returns AgentProcessStatusCode.COMPLETED
        every { agentProcess.run() } returns agentProcess
        every { agentPlatform.getAgentProcess("p1") } returns agentProcess
        return agentProcess
    }

    @Nested
    inner class Confirmation {

        @Test
        fun `acceptance is resolved through onResponse and the process resumes`() {
            val request = spyk(ConfirmationRequest(Payment(42), "Pay 42?"))
            val requestId = request.id // read outside verify: a spy call inside a verify block is not allowed
            val agentProcess = processWaitingOn(request)
            every { textCommunicator.communicateResult(any()) } returns "paid"

            val result = tools.confirmation(processId = "p1", confirmed = true)

            assertEquals("paid", result)
            verify(exactly = 1) {
                request.onResponse(
                    match { it.awaitableId == requestId && it.accepted },
                    agentProcess,
                )
            }
            // Base ConfirmationRequest behaviour is preserved: the payload is promoted
            verify(exactly = 1) { agentProcess.plusAssign(Payment(42)) }
            verify(exactly = 1) { agentProcess.run() }
        }

        @Test
        fun `rejection is resolved through onResponse and the process is not resumed`() {
            val request = spyk(ConfirmationRequest(Payment(42), "Pay 42?"))
            val requestId = request.id
            val agentProcess = processWaitingOn(request)

            val result = tools.confirmation(processId = "p1", confirmed = false)

            assertTrue(result.contains("rejected", ignoreCase = true), result)
            verify(exactly = 1) {
                request.onResponse(
                    match { it.awaitableId == requestId && !it.accepted },
                    agentProcess,
                )
            }
            verify(exactly = 0) { agentProcess.plusAssign(any<Any>()) }
            verify(exactly = 0) { agentProcess.run() }
        }

        @Test
        fun `unknown process is reported`() {
            every { agentPlatform.getAgentProcess("missing") } returns null

            val result = tools.confirmation(processId = "missing", confirmed = true)

            assertEquals("No process found with ID missing", result)
        }

        @Test
        fun `process without a pending confirmation is reported`() {
            val agentProcess = mockk<AgentProcess>(relaxed = true)
            every { agentProcess.lastResult() } returns "not an awaitable"
            every { agentPlatform.getAgentProcess("p1") } returns agentProcess

            val result = tools.confirmation(processId = "p1", confirmed = true)

            assertTrue(result.contains("No confirmation"), result)
            verify(exactly = 0) { agentProcess.run() }
        }
    }
}
