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
import com.embabel.common.util.EmbabelObjectMapperHolder
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

import io.a2a.spec.InternalError
import io.a2a.spec.AgentCard
import io.a2a.spec.CancelTaskRequest
import io.a2a.spec.GetTaskRequest
import io.a2a.spec.InvalidParamsError
import io.a2a.spec.InvalidRequestError
import io.a2a.spec.JSONParseError
import io.a2a.spec.JSONRPCError
import io.a2a.spec.JSONRPCErrorResponse
import io.a2a.spec.MethodNotFoundError
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendStreamingMessageRequest
import io.a2a.spec.TaskResubscriptionRequest
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
import tools.jackson.databind.ObjectMapper

/**
 * Registers A2A endpoints for the agent-to-agent communication protocol.
 * Each AgentCardHandler passed in results in the creation of
 * a distinct endpoint with its own agent card.
 */
@Component
class A2AEndpointRegistrar(
    private val agentCardHandlers: List<AgentCardHandler>,
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping,
    private val embabelObjectMapperHolder: EmbabelObjectMapperHolder,
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
        val endpointPath = "/.well-known/agent-card.json"// "/${agentCardHandler.path}/.well-known/agent.json"
        logger.info(
            "Registering web endpoint under {} for {}",
            endpointPath,
            agentCardHandler.infoString(verbose = true),
        )
        val agentCardGetMapping = RequestMappingInfo.paths(endpointPath)
            .methods(RequestMethod.GET)
            .produces(MediaType.APPLICATION_JSON_VALUE)
            .build()
        val achwf = AgentCardHandlerWebFacade(
            agentCardHandler,
            embabelObjectMapperHolder.get(),
        )
        requestMappingHandlerMapping.registerMapping(
            agentCardGetMapping,
            achwf,
            achwf::class.java.getMethod("agentCard", ServletRequest::class.java),
        )

        val jsonRpcPostMethod = achwf.javaClass.getMethod(
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
            achwf,
            jsonRpcPostMethod,
        )
    }
}

private class AgentCardHandlerWebFacade(
    val agentCardHandler: AgentCardHandler,
    val objectMapper: ObjectMapper,
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

    @ResponseBody
    fun handleJsonRpc(@RequestBody body: String): Any {
        // -- 1. Parse ------------------------------------------------------------
        val node: ObjectNode = try {
            objectMapper.readValue(body, ObjectNode::class.java)
        } catch (e: JacksonException) {
            logger.error("Malformed JSON in JSON-RPC request", e)
            return rpcError(id = null, JSONParseError())            // -32700 (id must be null)
        }

        val requestId: JsonNode? = node.get("id")
        val method: String? = if (node.get("method")?.isTextual == true) node.get("method").asText() else null

        logger.info("Received JSON-RPC request: method='{}', id='{}'",
            method, requestId,)

        // -- 2. Structural validation (-32600) ------------------------------------
        if (!isValidRequestEnvelope(node)) {
            logger.warn("Invalid JSON-RPC request structure: {}", body)
            return rpcError(id = requestId, InvalidRequestError())
        }

        // -- 3. Dispatch ----------------------------------------------------------
        return when (method) {
            SendStreamingMessageRequest.METHOD -> {
                logger.debug("Processing streaming request for method: {}", method)
                // For streaming requests, return the SseEmitter directly without wrapping
                try {
                    val request = objectMapper.convertValue(node, SendStreamingMessageRequest::class.java)
                    agentCardHandler.handleJsonRpcStream(request)
                } catch (e: JacksonException) {
                    logger.error("Failed to deserialize SendStreamingMessageRequest", e)
                    rpcError(id = requestId, InvalidParamsError())
                }
            }
            TaskResubscriptionRequest.METHOD -> {
                logger.debug("Processing resubscribe request for method: {}", method)
                try {
                    val request = objectMapper.convertValue(node, TaskResubscriptionRequest::class.java)
                    agentCardHandler.handleTaskResubscription(request)
                } catch (e: JacksonException) {
                    logger.error("Failed to deserialize TaskResubscriptionRequest", e)
                    rpcError(id = requestId, InvalidParamsError())
                }
            }
            else -> {
                logger.debug("Processing non-streaming request for method: {}", method)
                val request = try {
                    when (method) {
                        SendMessageRequest.METHOD -> objectMapper.convertValue(node, SendMessageRequest::class.java)
                        GetTaskRequest.METHOD -> objectMapper.convertValue(node, GetTaskRequest::class.java)
                        CancelTaskRequest.METHOD -> objectMapper.convertValue(node, CancelTaskRequest::class.java)
                        else -> {
                            logger.warn("Unsupported method: {}", method)
                            return rpcError(id = requestId, MethodNotFoundError())
                        }
                    }
                } catch (e: JacksonException) {
                    logger.error("Failed to deserialize request for method '{}'", method, e)
                    return rpcError(id = requestId, InvalidParamsError())
                }
                try {
                    ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(agentCardHandler.handleJsonRpc(request))
                }
                catch (e: VirtualMachineError) {   // Let Spring/the container handle it OOM, StackOverflow, etc.
                    logger.error("Fatal JVM error in A2A endpoint", e)
                    throw e
                }
                catch (e: Throwable) {
                    logger.error("Error handling JSON-RPC request: method='{}', id='{}'", method, requestId, e)
                    // Keep A2A errors (TaskNotFound etc.) as-is; wrap everything else as InternalError.
                    val error = e as? JSONRPCError ?: InternalError("Internal server error: ${e.message}")
                    rpcError(id = requestId, error as JSONRPCError)
                }
            }
        }
    }

    private fun isValidRequestEnvelope(node: ObjectNode): Boolean {
        if (node.get("jsonrpc")?.asString() != "2.0") return false
        val methodNode = node.get("method") ?: return false
        if (!methodNode.isString || methodNode.asString().isBlank()) return false
        return !invalidIdType(node.get("id"))
    }

    private fun invalidIdType(idNode: JsonNode?): Boolean {
        if (idNode == null || idNode.isNull) return false
        return !(idNode.isString || idNode.isNumber)
    }

    private fun rpcError(id: JsonNode?, error: JSONRPCError): ResponseEntity<JSONRPCErrorResponse> {
        // The SDK rejects non-scalar ids; JSON-RPC 2.0 says use null when the id
        // cannot be faithfully echoed (e.g. invalid request id).
        val safeId: Any? = when {
            id == null || id.isNull -> null
            id.isString -> id.asString()
            id.isNumber && id.canConvertToInt() -> id.asInt()
            else -> null
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(JSONRPCErrorResponse(safeId, error))
    }
}
