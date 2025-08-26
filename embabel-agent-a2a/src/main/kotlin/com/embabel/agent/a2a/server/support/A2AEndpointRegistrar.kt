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
package com.embabel.agent.a2a.server.support

import com.embabel.agent.a2a.server.AgentCardHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.a2a.spec.AgentCard
import io.a2a.spec.CancelTaskRequest
import io.a2a.spec.GetTaskRequest
import io.a2a.spec.JSONRPCError
import io.a2a.spec.JSONRPCErrorResponse
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendStreamingMessageRequest
import jakarta.servlet.ServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
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
@Profile("a2a")
class A2AEndpointRegistrar(
    private val agentCardHandlers: List<AgentCardHandler>,
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping,
    private val objectMapper: ObjectMapper,
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
        val endpointPath = "/${agentCardHandler.path}/.well-known/agent.json"
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
            objectMapper,
        )
        requestMappingHandlerMapping.registerMapping(
            agentCardGetMapping,
            achwf,
            achwf::class.java.getMethod("agentCard", ServletRequest::class.java),
        )

        val jsonRpcPostMethod = achwf.javaClass.getMethod(
            "handleJsonRpc",
            Map::class.java,
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
    fun handleJsonRpc(@RequestBody requestMap: Map<String, Any>): Any {
        return try {
            logger.debug("Received JSON-RPC request: {}", requestMap)
            val method = requestMap["method"] as? String

            return when (method) {
                SendStreamingMessageRequest.METHOD -> {
                    // For streaming requests, return the SseEmitter directly without wrapping
                    val request = objectMapper.convertValue(requestMap, SendStreamingMessageRequest::class.java)
                    agentCardHandler.handleJsonRpcStream(request)
                }
                else -> {
                    val request = when (method) {
                        SendMessageRequest.METHOD -> objectMapper.convertValue(requestMap, SendMessageRequest::class.java)
                        GetTaskRequest.METHOD -> objectMapper.convertValue(requestMap, GetTaskRequest::class.java)
                        CancelTaskRequest.METHOD -> objectMapper.convertValue(requestMap, CancelTaskRequest::class.java)
                        else -> {
                            throw UnsupportedOperationException("Method ${method} is not supported")
                        }
                    }
                    // Regular JSON-RPC handling
                    ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(agentCardHandler.handleJsonRpc(request))
                }
            }
        } catch (e: Exception) {
            val requestId = requestMap["id"]
            ResponseEntity.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    JSONRPCErrorResponse(
                        requestId,
                        JSONRPCError(
                            500,
                            "Internal server error: ${e.message}",
                            null
                        )
                    )
                )
        }
    }
}
