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
package com.embabel.agent.core.hitl

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AwaitingToolsTest {

    data class PaymentDetails(val amount: Double)

    private lateinit var mockAgentProcess: AgentProcess

    @BeforeEach
    fun setUp() {
        mockAgentProcess = mockk(relaxed = true)
        AgentProcess.set(mockAgentProcess)
    }

    @AfterEach
    fun tearDown() {
        AgentProcess.remove()
    }

    private fun createMockTool(name: String = "test_tool"): Tool {
        return Tool.create(name, "Test tool") { Tool.Result.text("executed") }
    }

    @Nested
    inner class ConfirmingToolTest {

        @Test
        fun `throws AwaitableResponseException with ConfirmationRequest`() {
            val delegate = createMockTool()
            val tool = ConfirmingTool(delegate) { "Confirm action?" }

            val exception = assertThrows<AwaitableResponseException> {
                tool.call("{}")
            }

            val awaitable = exception.awaitable
            assertInstanceOf(ConfirmationRequest::class.java, awaitable)
            assertEquals("Confirm action?", (awaitable as ConfirmationRequest<*>).message)
        }

        @Test
        fun `message provider receives input`() {
            val delegate = createMockTool()
            var receivedInput: String? = null
            val tool = ConfirmingTool(delegate) { input ->
                receivedInput = input
                "Confirm?"
            }

            assertThrows<AwaitableResponseException> {
                tool.call("{\"action\": \"delete\"}")
            }

            assertEquals("{\"action\": \"delete\"}", receivedInput)
        }

        @Test
        fun `preserves delegate definition`() {
            val delegate = createMockTool("my_tool")
            val tool = ConfirmingTool(delegate) { "Confirm?" }

            assertEquals("my_tool", tool.definition.name)
            assertEquals(delegate.definition.description, tool.definition.description)
        }

        @Test
        fun `extension function creates ConfirmingTool`() {
            val delegate = createMockTool()
            val tool = delegate.withConfirmation("Are you sure?")

            assertInstanceOf(ConfirmingTool::class.java, tool)
        }

        @Test
        fun `extension function with lambda creates ConfirmingTool`() {
            val delegate = createMockTool()
            val tool = delegate.withConfirmation { "Confirm $it?" }

            assertInstanceOf(ConfirmingTool::class.java, tool)
        }
    }

    @Nested
    inner class ConditionalAwaitingToolTest {

        @Test
        fun `proceeds normally when decider returns null`() {
            val delegate = createMockTool()
            val tool = ConditionalAwaitingTool(delegate) { null }

            val result = tool.call("{}")

            assertInstanceOf(Tool.Result.Text::class.java, result)
            assertEquals("executed", (result as Tool.Result.Text).content)
        }

        @Test
        fun `throws AwaitableResponseException when decider returns awaitable`() {
            val delegate = createMockTool()
            val tool = ConditionalAwaitingTool(delegate) { context ->
                ConfirmationRequest(context.input, "Confirm?")
            }

            val exception = assertThrows<AwaitableResponseException> {
                tool.call("{}")
            }

            assertInstanceOf(ConfirmationRequest::class.java, exception.awaitable)
        }

        @Test
        fun `decider receives context with input and process`() {
            val delegate = createMockTool()
            var capturedContext: AwaitContext? = null
            val tool = ConditionalAwaitingTool(delegate) { context ->
                capturedContext = context
                null
            }

            tool.call("{\"key\": \"value\"}")

            assertNotNull(capturedContext)
            assertEquals("{\"key\": \"value\"}", capturedContext!!.input)
            assertEquals(mockAgentProcess, capturedContext!!.agentProcess)
            assertEquals(delegate, capturedContext!!.tool)
        }

        @Test
        fun `throws when no AgentProcess available`() {
            AgentProcess.remove()
            val delegate = createMockTool()
            val tool = ConditionalAwaitingTool(delegate) { null }

            val exception = assertThrows<IllegalStateException> {
                tool.call("{}")
            }

            assertTrue(exception.message?.contains("No AgentProcess") == true)
        }

        @Test
        fun `extension function creates ConditionalAwaitingTool`() {
            val delegate = createMockTool()
            val tool = delegate.withAwaiting { null }

            assertInstanceOf(ConditionalAwaitingTool::class.java, tool)
        }
    }

    @Nested
    inner class TypeRequestingToolTest {

        @Test
        fun `throws AwaitableResponseException with TypeRequest`() {
            val delegate = createMockTool()
            val tool = TypeRequestingTool(delegate, PaymentDetails::class.java)

            val exception = assertThrows<AwaitableResponseException> {
                tool.call("{}")
            }

            val awaitable = exception.awaitable
            assertInstanceOf(TypeRequest::class.java, awaitable)
            assertEquals(PaymentDetails::class.java, (awaitable as TypeRequest<*>).type)
        }

        @Test
        fun `includes message from provider`() {
            val delegate = createMockTool()
            val tool = TypeRequestingTool(delegate, PaymentDetails::class.java) {
                "Please provide payment"
            }

            val exception = assertThrows<AwaitableResponseException> {
                tool.call("{}")
            }

            val request = exception.awaitable as TypeRequest<*>
            assertEquals("Please provide payment", request.message)
        }

        @Test
        fun `preserves delegate definition`() {
            val delegate = createMockTool("payment_tool")
            val tool = TypeRequestingTool(delegate, PaymentDetails::class.java)

            assertEquals("payment_tool", tool.definition.name)
        }

        @Test
        fun `extension function creates TypeRequestingTool`() {
            val delegate = createMockTool()
            val tool = delegate.requireType(PaymentDetails::class.java)

            assertInstanceOf(TypeRequestingTool::class.java, tool)
        }

        @Test
        fun `reified extension function creates TypeRequestingTool`() {
            val delegate = createMockTool()
            val tool = delegate.requireType<PaymentDetails>("Enter payment")

            assertInstanceOf(TypeRequestingTool::class.java, tool)

            val exception = assertThrows<AwaitableResponseException> {
                tool.call("{}")
            }
            val request = exception.awaitable as TypeRequest<*>
            assertEquals(PaymentDetails::class.java, request.type)
            assertEquals("Enter payment", request.message)
        }
    }
}
