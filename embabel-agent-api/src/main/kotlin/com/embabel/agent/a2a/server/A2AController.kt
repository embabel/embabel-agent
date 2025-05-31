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
package com.embabel.agent.a2a.server

import com.embabel.agent.a2a.spec.*
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Expose A2A endpoints for the agent-to-agent communication protocol.
 */
@RestController
@Profile("a2a")
@RequestMapping("/a2a")
class A2AController(
    private val a2AMessageHandler: A2AMessageHandler,
) {

    private val logger = LoggerFactory.getLogger(A2AController::class.java)

    init {
        logger.info("'a2a' Spring profile set: Exposing A2A server")
    }

    @GetMapping("/.well-known/agent.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun agentCard(request: HttpServletRequest): AgentCard {
        val scheme = request.scheme
        val serverName = request.serverName
        val serverPort = request.serverPort

        val hostingUrl = "$scheme://$serverName:$serverPort/a2a"

        val agentCard = AgentCard(
            name = "Demo Agent",
            description = "A demo agent for the Embabel platform.",
            url = hostingUrl,
            provider = AgentProvider("Embabel", "https://embabel.com"),
            version = DEFAULT_VERSION,
            documentationUrl = "https://embabel.com/docs",
            capabilities = AgentCapabilities(
                streaming = false,
                pushNotifications = false,
                stateTransitionHistory = false,
            ),
            securitySchemes = null,
            security = null,
            defaultInputModes = listOf("application/json", "text/plain"),
            defaultOutputModes = listOf("application/json", "text/plain"),
            skills = listOf(
                AgentSkill(
                    id = "echo",
                    name = "Echo",
                    description = "Echoes messages.",
                    tags = listOf("test"),
                    examples = listOf("Say hello!"),
                ),
            ),
            supportsAuthenticatedExtendedCard = false,
        )
        logger.info("Returning agent card: {}", agentCard)
        return agentCard
    }

    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun handleJsonRpc(
        @RequestBody @Schema(description = "A2A message send params")
        request: JSONRPCRequest,
    ): ResponseEntity<JSONRPCResponse> {
        return a2AMessageHandler.handleJsonRpc(request)
    }
}

