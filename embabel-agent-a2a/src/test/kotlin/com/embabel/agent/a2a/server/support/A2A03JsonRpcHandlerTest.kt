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
package com.embabel.agent.a2a.server.support

import com.embabel.agent.a2a.server.A2ARequestHandler
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import org.a2aproject.sdk.compat03.spec.InternalError_v0_3
import org.a2aproject.sdk.compat03.spec.InvalidRequestError_v0_3
import org.a2aproject.sdk.compat03.spec.JSONParseError_v0_3
import org.a2aproject.sdk.compat03.spec.SendMessageRequest_v0_3
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class A2A03JsonRpcHandlerTest {

    private val requestHandler = mockk<A2ARequestHandler>()

    private val handler = A2A03JsonRpcHandler(requestHandler)

    private fun errorCodeOf(response: Any): Int =
        JsonParser.parseString(response as String).asJsonObject["error"].asJsonObject["code"].asInt

    private fun sendMessageBody(method: String = "\"${SendMessageRequest_v0_3.METHOD}\""): String = """
        {"jsonrpc": "2.0", "id": "req-1", "method": $method, "params": {"message": {
          "kind": "message", "messageId": "msg-1", "role": "user",
          "parts": [{"kind": "text", "text": "Hello"}]}}}
        """.trimIndent()

    @Nested
    inner class ErrorCodes {

        @Test
        fun `should return internal error when the request handler throws IllegalStateException`() {
            every { requestHandler.handleJsonRpc(any()) } throws IllegalStateException("handler failed")

            assertEquals(InternalError_v0_3.DEFAULT_CODE, errorCodeOf(handler.handle(sendMessageBody(), "req-1")))
        }

        @Test
        fun `should return invalid request when the body is valid JSON but not an object`() {
            assertEquals(InvalidRequestError_v0_3.DEFAULT_CODE, errorCodeOf(handler.handle("[]", null)))
        }

        @Test
        fun `should return parse error when the body is not valid JSON`() {
            assertEquals(JSONParseError_v0_3.DEFAULT_CODE, errorCodeOf(handler.handle("{", null)))
        }

        @Test
        fun `should return invalid request when the method is not a string`() {
            val body = sendMessageBody(method = "{}")

            assertEquals(InvalidRequestError_v0_3.DEFAULT_CODE, errorCodeOf(handler.handle(body, "req-1")))
        }
    }
}
