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

import com.embabel.agent.a2a.server.AgentCardHandler
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.a2a.spec.AgentCard
import io.a2a.spec.AuthenticatedExtendedCardNotConfiguredError
import io.a2a.spec.CancelTaskRequest
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest
import io.a2a.spec.GetAuthenticatedExtendedCardRequest
import io.a2a.spec.GetTaskPushNotificationConfigRequest
import io.a2a.spec.GetTaskRequest
import io.a2a.spec.InternalError
import io.a2a.spec.InvalidParamsError
import io.a2a.spec.InvalidRequestError
import io.a2a.spec.JSONParseError
import io.a2a.spec.JSONRPCError
import io.a2a.spec.JSONRPCErrorResponse
import io.a2a.spec.JSONRPCRequest
import io.a2a.spec.JSONRPCResponse
import io.a2a.spec.ListTaskPushNotificationConfigRequest
import io.a2a.spec.MethodNotFoundError
import io.a2a.spec.PushNotificationNotSupportedError
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendStreamingMessageRequest
import io.a2a.spec.SetTaskPushNotificationConfigRequest
import io.a2a.spec.TaskResubscriptionRequest
import io.a2a.util.Utils
import jakarta.servlet.ServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Registers A2A endpoints for the agent-to-agent communication protocol.
 * Each AgentCardHandler passed in results in the creation of
 * a distinct endpoint with its own agent card.
 */
@Component
class A2AEndpointRegistrar(
    private val agentCardHandlers: List<AgentCardHandler>,
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping,
) {

    private val logger = LoggerFactory.getLogger(A2AEndpointRegistrar::class.java)

    @EventListener
    fun onApplicationReady(event: ApplicationReadyEvent) {
        logger.info("Registering ${agentCardHandlers.size} A2A endpoints")
        agentCardHandlers.forEach { endpoint ->
            registerWebEndpoints(endpoint)
        }
    }

    private fun registerWebEndpoints(agentCardHandler: AgentCardHandler) {
        // A2A 0.3 serves the card at agent-card.json; agent.json (A2A 0.2) is kept for older clients
        val agentCardPaths = arrayOf(
            "/${agentCardHandler.path}/.well-known/agent-card.json",
            "/${agentCardHandler.path}/.well-known/agent.json",
        )
        logger.info(
            "Registering web endpoint under {} for {}",
            agentCardPaths.joinToString(),
            agentCardHandler.infoString(verbose = true),
        )
        val agentCardGetMapping = RequestMappingInfo.paths(*agentCardPaths)
            .methods(RequestMethod.GET)
            .produces(MediaType.APPLICATION_JSON_VALUE)
            .build()
        val agentCardHandlerWebFacade = AgentCardHandlerWebFacade(agentCardHandler)
        requestMappingHandlerMapping.registerMapping(
            agentCardGetMapping,
            agentCardHandlerWebFacade,
            agentCardHandlerWebFacade::class.java.getMethod("agentCard", ServletRequest::class.java),
        )

        val jsonRpcPostMethod = agentCardHandlerWebFacade.javaClass.getMethod(
            "handleJsonRpc",
            String::class.java,
        )
        val jsonRpcPostMapping = RequestMappingInfo.paths(agentCardHandler.path)
            .methods(RequestMethod.POST)
            .consumes(MediaType.APPLICATION_JSON_VALUE)
            .produces(MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
        requestMappingHandlerMapping.registerMapping(
            jsonRpcPostMapping,
            agentCardHandlerWebFacade,
            jsonRpcPostMethod,
        )
    }
}

/**
 * Name of the JSON-RPC request and response member that holds the id
 */
private const val ID = "id"

private class AgentCardHandlerWebFacade(
    val agentCardHandler: AgentCardHandler,
) {
    private val logger = LoggerFactory.getLogger(AgentCardHandlerWebFacade::class.java)

    @ResponseBody
    fun agentCard(servletRequest: ServletRequest): ResponseEntity<AgentCard> {
        val agentCard = agentCardHandler.agentCard(
            scheme = servletRequest.scheme,
            host = servletRequest.serverName,
            port = servletRequest.serverPort,
        )
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(agentCard)
    }

    /**
     * Handles a JSON-RPC request.
     * Requests are read and responses written with the A2A SDK's own JSON mapper,
     * which knows the A2A types, and every failure is answered with a JSON-RPC error.
     */
    @ResponseBody
    fun handleJsonRpc(@RequestBody body: String): Any {
        var requestId: JsonNode? = null
        return try {
            val request = readTree(body)
            requestId = idOf(request)
            if (request.path("jsonrpc").asText() != JSONRPCRequest.JSONRPC_VERSION) {
                throw InvalidRequestError("A JSON-RPC request must have \"jsonrpc\": \"${JSONRPCRequest.JSONRPC_VERSION}\"")
            }
            val method = request.get("method")?.takeIf { it.isTextual }?.asText()
                ?: throw InvalidRequestError("A JSON-RPC request must have a string \"method\"")
            logger.info("Received JSON-RPC request: method='{}', id='{}'", method, requestId)
            when (method) {
                // message/stream: runs an agent and answers with a stream of the task's events
                SendStreamingMessageRequest.METHOD ->
                    agentCardHandler.handleJsonRpcStream(read(request, SendStreamingMessageRequest::class.java))

                // tasks/resubscribe: answers with a stream of an existing task's events, past and future
                TaskResubscriptionRequest.METHOD ->
                    agentCardHandler.handleJsonRpcStream(read(request, TaskResubscriptionRequest::class.java))

                // message/send: runs an agent and answers with the finished task
                SendMessageRequest.METHOD ->
                    jsonResponse(agentCardHandler.handleJsonRpc(read(request, SendMessageRequest::class.java)), requestId)

                // tasks/get: answers with the current state of a task
                GetTaskRequest.METHOD ->
                    jsonResponse(agentCardHandler.handleJsonRpc(read(request, GetTaskRequest::class.java)), requestId)

                // tasks/cancel: answers whether a task could be canceled
                CancelTaskRequest.METHOD ->
                    jsonResponse(agentCardHandler.handleJsonRpc(read(request, CancelTaskRequest::class.java)), requestId)

                // tasks/pushNotificationConfig/*: the agent card declares no push notifications
                SetTaskPushNotificationConfigRequest.METHOD,
                GetTaskPushNotificationConfigRequest.METHOD,
                ListTaskPushNotificationConfigRequest.METHOD,
                DeleteTaskPushNotificationConfigRequest.METHOD -> throw PushNotificationNotSupportedError()

                // agent/getAuthenticatedExtendedCard: the agent card declares no authenticated extended card
                GetAuthenticatedExtendedCardRequest.METHOD -> throw AuthenticatedExtendedCardNotConfiguredError()

                // Any other method is not an A2A method
                else -> throw MethodNotFoundError()
            }
        } catch (e: JSONRPCError) {
            errorResponse(requestId, e)
        } catch (e: Throwable) {
            // Throwable, not Exception: errors such as kotlin.NotImplementedError must still get a JSON-RPC response
            logger.error("Error handling JSON-RPC request: id='{}', error={}", requestId, e.message, e)
            errorResponse(requestId, InternalError("Internal error: ${e.message}"))
        }
    }

    private fun readTree(body: String): ObjectNode {
        val request = try {
            Utils.OBJECT_MAPPER.readTree(body)
        } catch (e: JsonProcessingException) {
            throw JSONParseError(e.originalMessage)
        }
        return request as? ObjectNode ?: throw InvalidRequestError("A JSON-RPC request must be a JSON object")
    }

    /**
     * @return the id as sent, or null if the request has none
     */
    private fun idOf(request: JsonNode): JsonNode? {
        val id = request.get(ID)
        return when {
            id == null || id.isNull -> null
            id.isTextual || id.isNumber -> id
            // An object, an array or a boolean
            else -> throw InvalidRequestError("A JSON-RPC request must have a string, number or null \"id\"")
        }
    }

    /**
     * Reads the request as the given A2A SDK request type.
     * The SDK reads only string ids and integer ids that fit in an Int, so any other number is read as a string.
     * The SDK rejects a null id, so a request with a null id is read as a request without an id.
     * Neither change reaches the client: [jsonResponse] answers with the id as sent.
     */
    private fun <T> read(request: ObjectNode, type: Class<T>): T =
        try {
            // get returns null when the request has no id member, and a NullNode when the id is JSON null
            val id = request.get(ID)
            val sdkReadableRequest = when {
                id == null || id.isTextual || (id.isIntegralNumber && id.canConvertToInt()) -> request
                id.isNull -> request.deepCopy().without(ID)
                else -> request.deepCopy().put(ID, id.asText())
            }
            // A malformed request fails with one of the two exceptions caught below;
            // handleJsonRpc answers any other failure with an internal error
            Utils.OBJECT_MAPPER.treeToValue(sdkReadableRequest, type)
        } catch (e: JsonProcessingException) {
            throw InvalidParamsError(e.originalMessage)
        } catch (e: IllegalArgumentException) {
            throw InvalidParamsError(e.message)
        }

    private fun errorResponse(requestId: JsonNode?, error: JSONRPCError): ResponseEntity<String> {
        logger.warn("Returning JSON-RPC error: id='{}', code={}, message='{}'", requestId, error.code, error.message)
        return jsonResponse(JSONRPCErrorResponse(null, error), requestId)
    }

    private fun jsonResponse(response: JSONRPCResponse<*>, requestId: JsonNode?): ResponseEntity<String> {
        val json = Utils.OBJECT_MAPPER.valueToTree<ObjectNode>(response)
        // JSON-RPC 2.0 requires the id member: the id as sent, or null when the request id could not be read
        json.set<JsonNode>(ID, requestId ?: NullNode.instance)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(Utils.OBJECT_MAPPER.writeValueAsString(json))
    }
}
