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

import com.embabel.agent.core.AgentProcess
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TypeRequestTest {

    data class PaymentDetails(val cardNumber: String, val amount: Double)

    @Nested
    inner class Construction {

        @Test
        fun `creates with type only`() {
            val request = TypeRequest(PaymentDetails::class.java)

            assertEquals(PaymentDetails::class.java, request.type)
            assertEquals(PaymentDetails::class.java, request.payload)
            assertNull(request.message)
            assertNull(request.hint)
        }

        @Test
        fun `creates with message`() {
            val request = TypeRequest(
                type = PaymentDetails::class.java,
                message = "Please provide payment details",
            )

            assertEquals("Please provide payment details", request.message)
        }

        @Test
        fun `creates with hint`() {
            val hint = PaymentDetails("****1234", 100.0)
            val request = TypeRequest(
                type = PaymentDetails::class.java,
                hint = hint,
            )

            assertEquals(hint, request.hint)
        }

        @Test
        fun `reified factory creates correct type`() {
            val request = typeRequest<PaymentDetails>(
                message = "Enter payment",
            )

            assertEquals(PaymentDetails::class.java, request.type)
            assertEquals("Enter payment", request.message)
        }
    }

    @Nested
    inner class Response {

        @Test
        fun `onResponse adds value to blackboard`() {
            val request = TypeRequest(PaymentDetails::class.java)
            val payment = PaymentDetails("4111111111111111", 50.0)
            val response = TypeResponse(
                value = payment,
                awaitableId = request.id,
            )
            val agentProcess = mockk<AgentProcess>(relaxed = true)

            val impact = request.onResponse(response, agentProcess)

            assertEquals(ResponseImpact.UPDATED, impact)
            verify { agentProcess.plusAssign(payment) }
        }
    }

    @Nested
    inner class InfoString {

        @Test
        fun `infoString includes type name`() {
            val request = TypeRequest(PaymentDetails::class.java)
            val info = request.infoString(verbose = false, indent = 0)

            assertTrue(info.contains("PaymentDetails"))
            assertTrue(info.contains("TypeRequest"))
        }

        @Test
        fun `infoString includes message when present`() {
            val request = TypeRequest(
                type = PaymentDetails::class.java,
                message = "Please pay",
            )
            val info = request.infoString(verbose = false, indent = 0)

            assertTrue(info.contains("Please pay"))
        }
    }
}
