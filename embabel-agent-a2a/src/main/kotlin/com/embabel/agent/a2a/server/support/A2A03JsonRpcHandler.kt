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

import com.embabel.agent.a2a.server.A2AProtocolVersion
import com.embabel.agent.a2a.server.A2ARequestHandler
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import org.a2aproject.sdk.compat03.conversion.ErrorConverter_v0_3
import org.a2aproject.sdk.compat03.conversion.mappers.domain.EventKindMapper_v0_3
import org.a2aproject.sdk.compat03.conversion.mappers.domain.TaskMapper_v0_3
import org.a2aproject.sdk.compat03.conversion.mappers.params.CancelTaskParamsMapper_v0_3
import org.a2aproject.sdk.compat03.conversion.mappers.params.MessageSendParamsMapper_v0_3
import org.a2aproject.sdk.compat03.conversion.mappers.params.TaskIdParamsMapper_v0_3
import org.a2aproject.sdk.compat03.conversion.mappers.params.TaskQueryParamsMapper_v0_3
import org.a2aproject.sdk.compat03.json.JsonUtil_v0_3
import org.a2aproject.sdk.compat03.spec.A2AErrorCodes_v0_3.AUTHENTICATED_EXTENDED_CARD_NOT_CONFIGURED_ERROR_CODE
import org.a2aproject.sdk.compat03.spec.AuthenticatedExtendedCardNotConfiguredError_v0_3
import org.a2aproject.sdk.compat03.spec.CancelTaskRequest_v0_3
import org.a2aproject.sdk.compat03.spec.CancelTaskResponse_v0_3
import org.a2aproject.sdk.compat03.spec.DeleteTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetAuthenticatedExtendedCardRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetTaskRequest_v0_3
import org.a2aproject.sdk.compat03.spec.GetTaskResponse_v0_3
import org.a2aproject.sdk.compat03.spec.InternalError_v0_3
import org.a2aproject.sdk.compat03.spec.InvalidParamsError_v0_3
import org.a2aproject.sdk.compat03.spec.InvalidRequestError_v0_3
import org.a2aproject.sdk.compat03.spec.JSONParseError_v0_3
import org.a2aproject.sdk.compat03.spec.JSONRPCErrorResponse_v0_3
import org.a2aproject.sdk.compat03.spec.JSONRPCError_v0_3
import org.a2aproject.sdk.compat03.spec.ListTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.MethodNotFoundError_v0_3
import org.a2aproject.sdk.compat03.spec.PushNotificationNotSupportedError_v0_3
import org.a2aproject.sdk.compat03.spec.SendMessageRequest_v0_3
import org.a2aproject.sdk.compat03.spec.SendMessageResponse_v0_3
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageRequest_v0_3
import org.a2aproject.sdk.compat03.spec.SetTaskPushNotificationConfigRequest_v0_3
import org.a2aproject.sdk.compat03.spec.TaskResubscriptionRequest_v0_3
import org.a2aproject.sdk.compat03.util.Utils_v0_3
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest
import org.a2aproject.sdk.spec.A2AError
import org.slf4j.LoggerFactory

/**
 * Serves A2A 0.3 JSON-RPC requests with an A2A 1.0 [A2ARequestHandler].
 * Requests are read as A2A 0.3, converted to A2A 1.0 with the A2A Java SDK's mappers,
 * handled, and the responses converted back to A2A 0.3.
 * Stream events are written as A2A 0.3 by the streaming handler.
 */
internal class A2A03JsonRpcHandler(
    private val requestHandler: A2ARequestHandler,
) {

    private val logger = LoggerFactory.getLogger(A2A03JsonRpcHandler::class.java)

    /**
     * @param requestId the id of the request, as sent
     * @return the JSON response, or the stream for a streaming request
     */
    fun handle(body: String, requestId: Any?): Any {
        return try {
            val method = methodOf(body)
            logger.info("Received A2A 0.3 JSON-RPC request: method='{}', id='{}'", method, requestId)
            when (method) {
                SendMessageRequest_v0_3.METHOD -> {
                    val request = read(body, SendMessageRequest_v0_3::class.java)
                    val params = MessageSendParamsMapper_v0_3.INSTANCE.toV10(request.params)
                    toJson(requestHandler.handleJsonRpc(SendMessageRequest(requestId, params)), requestId)
                }

                GetTaskRequest_v0_3.METHOD -> {
                    val request = read(body, GetTaskRequest_v0_3::class.java)
                    val params = TaskQueryParamsMapper_v0_3.INSTANCE.toV10(request.params)
                    toJson(requestHandler.handleJsonRpc(GetTaskRequest(requestId, params)), requestId)
                }

                CancelTaskRequest_v0_3.METHOD -> {
                    val request = read(body, CancelTaskRequest_v0_3::class.java)
                    val params = CancelTaskParamsMapper_v0_3.INSTANCE.toV10(request.params)
                    toJson(requestHandler.handleJsonRpc(CancelTaskRequest(requestId, params)), requestId)
                }

                SendStreamingMessageRequest_v0_3.METHOD -> {
                    val request = read(body, SendStreamingMessageRequest_v0_3::class.java)
                    val params = MessageSendParamsMapper_v0_3.INSTANCE.toV10(request.params)
                    requestHandler.handleJsonRpcStream(
                        SendStreamingMessageRequest(requestId, params),
                        A2AProtocolVersion.V0_3,
                    )
                }

                TaskResubscriptionRequest_v0_3.METHOD -> {
                    val request = read(body, TaskResubscriptionRequest_v0_3::class.java)
                    val params = TaskIdParamsMapper_v0_3.INSTANCE.toV10(request.params)
                    requestHandler.handleJsonRpcStream(
                        SubscribeToTaskRequest(requestId, params),
                        A2AProtocolVersion.V0_3,
                    )
                }

                // The agent card declares no push notifications and no authenticated extended card
                SetTaskPushNotificationConfigRequest_v0_3.METHOD,
                GetTaskPushNotificationConfigRequest_v0_3.METHOD,
                ListTaskPushNotificationConfigRequest_v0_3.METHOD,
                DeleteTaskPushNotificationConfigRequest_v0_3.METHOD -> errorJson(requestId, PushNotificationNotSupportedError_v0_3())

                GetAuthenticatedExtendedCardRequest_v0_3.METHOD ->
                    errorJson(
                        requestId,
                        AuthenticatedExtendedCardNotConfiguredError_v0_3(
                            AUTHENTICATED_EXTENDED_CARD_NOT_CONFIGURED_ERROR_CODE,
                            "Authenticated Extended Card not configured",
                            null,
                        ),
                    )

                else -> errorJson(requestId, MethodNotFoundError_v0_3())
            }
        } catch (e: JSONRPCError_v0_3) {
            errorJson(requestId, e)
        } catch (e: A2AError) {
            errorJson(requestId, ErrorConverter_v0_3.convertA2AError(e))
        } catch (e: Throwable) {
            logger.error("Error handling A2A 0.3 JSON-RPC request: {}", e.message, e)
            errorJson(requestId, InternalError_v0_3("Internal server error: ${e.message}"))
        }
    }

    private fun methodOf(body: String): String {
        val request = try {
            JsonParser.parseString(body)
        } catch (e: JsonSyntaxException) {
            throw JSONParseError_v0_3(e.message)
        }
        if (!request.isJsonObject) {
            // Valid JSON that is not a request object is an invalid request, not a parse error
            throw InvalidRequestError_v0_3("A JSON-RPC request must be a JSON object")
        }
        val method = request.asJsonObject.get("method")
        if (method == null || !method.isJsonPrimitive || !method.asJsonPrimitive.isString) {
            throw InvalidRequestError_v0_3("A JSON-RPC request must have a string method")
        }
        return method.asString
    }

    private fun <T> read(body: String, type: Class<T>): T =
        try {
            JsonUtil_v0_3.fromJson(body, type)
        } catch (e: Exception) {
            throw InvalidParamsError_v0_3(e.message)
        }

    private fun toJson(response: A2AResponse<*>, requestId: Any?): String {
        response.error?.let { return errorJson(requestId, ErrorConverter_v0_3.convertA2AError(it)) }
        val v03Response = when (response) {
            is SendMessageResponse -> SendMessageResponse_v0_3(
                requestId,
                EventKindMapper_v0_3.INSTANCE.fromV10(response.result),
            )

            is GetTaskResponse -> GetTaskResponse_v0_3(requestId, TaskMapper_v0_3.INSTANCE.fromV10(response.result))
            is CancelTaskResponse -> CancelTaskResponse_v0_3(requestId, TaskMapper_v0_3.INSTANCE.fromV10(response.result))
            else -> throw IllegalArgumentException("Unsupported response type: ${response::class.java.name}")
        }
        return Utils_v0_3.toJsonString(v03Response)
    }

    private fun errorJson(requestId: Any?, error: JSONRPCError_v0_3): String {
        logger.warn("Returning A2A 0.3 JSON-RPC error: id='{}', code={}, message='{}'", requestId, error.code, error.message)
        return Utils_v0_3.toJsonString(JSONRPCErrorResponse_v0_3(requestId, error))
    }
}
