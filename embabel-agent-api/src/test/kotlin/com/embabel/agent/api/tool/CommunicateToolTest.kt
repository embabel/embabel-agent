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
package com.embabel.agent.api.tool

import com.embabel.agent.api.channel.MessageOutputChannelEvent
import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.channel.OutputChannelEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.core.ProcessOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommunicateToolTest {

    private fun mockAgentProcess(outputChannel: OutputChannel): AgentProcess =
        mockk {
            every { id } returns "communicate-test"
            every { processOptions } returns ProcessOptions(outputChannel = outputChannel)
        }

    @Nested
    inner class ToolDefinition {

        @Test
        fun `create returns communicate tool with message parameter`() {
            val tool = CommunicateTool.create()

            assertEquals(CommunicateTool.NAME, tool.definition.name)
            assertTrue(tool.definition.description.contains("permanent message"))
            assertEquals(1, tool.definition.inputSchema.parameters.size)
            val parameter = tool.definition.inputSchema.parameters.single()
            assertEquals("message", parameter.name)
            assertEquals(Tool.ParameterType.STRING, parameter.type)
            assertEquals("The message to send to the user", parameter.description)
            assertTrue(parameter.required)
        }
    }

    @Nested
    inner class WithoutActiveProcess {

        @Test
        fun `missing message parameter returns error`() {
            val result = CommunicateTool.create().call("""{"other":"x"}""")

            val error = assertIs<Tool.Result.Error>(result)
            assertEquals("Missing 'message' parameter", error.message)
        }

        @Test
        fun `notes message when no agent process is bound`() {
            val result = CommunicateTool.create().call("""{"message":"hello user"}""")

            val text = assertIs<Tool.Result.Text>(result)
            assertEquals(
                "Message noted (no active process to deliver it).",
                text.content,
            )
        }
    }

    @Nested
    inner class WithActiveProcess {

        @Test
        fun `sends assistant message on output channel`() {
            val channel = mockk<OutputChannel>(relaxed = true)
            val event = slot<OutputChannelEvent>()
            val process = mockAgentProcess(outputChannel = channel)

            process.withCurrent {
                val result = CommunicateTool.create().call("""{"message":"PR ready at https://example.com"}""")

                val text = assertIs<Tool.Result.Text>(result)
                assertEquals("Message sent to user.", text.content)
            }

            verify(exactly = 1) { channel.send(capture(event)) }
            val messageEvent = assertIs<MessageOutputChannelEvent>(event.captured)
            assertEquals(process.id, messageEvent.processId)
            assertEquals("PR ready at https://example.com", messageEvent.message.content)
        }

        @Test
        fun `rejects empty message content when process is active`() {
            val channel = mockk<OutputChannel>(relaxed = true)
            val process = mockAgentProcess(outputChannel = channel)

            process.withCurrent {
                assertFailsWith<IllegalArgumentException> {
                    CommunicateTool.create().call("""{"message":""}""")
                }
            }

            verify(exactly = 0) { channel.send(any()) }
        }

        @Test
        fun `propagates output channel failure instead of reporting success`() {
            val channel = mockk<OutputChannel>()
            every { channel.send(any()) } throws IllegalStateException("channel unavailable")
            val process = mockAgentProcess(outputChannel = channel)

            val failure = assertFailsWith<IllegalStateException> {
                process.withCurrent {
                    CommunicateTool.create().call("""{"message":"important update"}""")
                }
            }

            assertEquals("channel unavailable", failure.message)
        }
    }
}
