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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Expose A2A endpoints for the agent-to-agent communication protocol.
 */
@RestController
@Profile("a2a")
@RequestMapping("/a2a")
class A2AController {

    private val logger = LoggerFactory.getLogger(A2AController::class.java)

    init {
        logger.info("'a2a' Spring profile set: Exposing A2A server")
    }

    @GetMapping("/.well-known/agent.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun agentCard(request: HttpServletRequest): AgentCard {
        val scheme = request.scheme
        val serverName = request.serverName
        val serverPort = request.serverPort

        val hostingUrl = "$scheme://$serverName:$serverPort"

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
        "/message/send",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun sendMessage(@RequestBody @Schema(description = "A2A message send params") params: MessageSendParams): ResponseEntity<JSONRPCResponse> {
        logger.info("Received message: {}", params)
        val task = Task(
            id = params.message.taskId ?: "task-1",
            contextId = params.message.contextId ?: "ctx-1",
            status = TaskStatus(TaskState.COMPLETED),
            history = listOf(params.message),
            artifacts = emptyList(),
            metadata = null
        )
        val result = JSONRPCSuccessResponse(id = params.message.messageId, result = task)
        return ResponseEntity.ok(result)
    }

    // --- message/stream ---
    @PostMapping("/message/stream", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun streamMessage(@RequestBody params: MessageSendParams): SseEmitter {
        logger.info("Stream message: {}", params)

        val emitter = SseEmitter()
        // Dummy event stream: send a status update and complete
        val statusEvent = TaskStatusUpdateEvent(
            taskId = params.message.taskId ?: "task-1",
            contextId = params.message.contextId ?: "ctx-1",
            status = TaskStatus(TaskState.WORKING),
        )
        val completeEvent = TaskStatusUpdateEvent(
            taskId = params.message.taskId ?: "task-1",
            contextId = params.message.contextId ?: "ctx-1",
            status = TaskStatus(TaskState.COMPLETED),
            final = true
        )
        try {
            emitter.send(statusEvent)
            Thread.sleep(500)
            emitter.send(completeEvent)
            emitter.complete()
        } catch (e: Exception) {
            emitter.completeWithError(e)
        }
        return emitter
    }

    @PostMapping(
        "/tasks/get",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getTask(@RequestBody params: TaskQueryParams): ResponseEntity<JSONRPCResponse> {
        logger.info("getTask: {}", params)

        // Dummy implementation: returns a sample task
        val task = Task(
            id = params.id,
            contextId = "ctx-1",
            status = TaskStatus(TaskState.COMPLETED),
            history = emptyList(),
            artifacts = emptyList(),
            metadata = null
        )
        val result = JSONRPCSuccessResponse(id = params.id, result = task)
        return ResponseEntity.ok(result)
    }

    @PostMapping(
        "/tasks/cancel",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun cancelTask(@RequestBody params: TaskIdParams): ResponseEntity<JSONRPCResponse> {
        TODO("Implement task cancellation logic")
    }

    // --- tasks/pushNotificationConfig/set ---
    @PostMapping(
        "/tasks/pushNotificationConfig/set",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun setPushNotificationConfig(@RequestBody params: TaskPushNotificationConfig): ResponseEntity<JSONRPCResponse> {
        // Dummy: echo back
        val result = JSONRPCSuccessResponse(id = params.taskId, result = params)
        return ResponseEntity.ok(result)
    }

    // --- tasks/pushNotificationConfig/get ---
    @PostMapping(
        "/tasks/pushNotificationConfig/get",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getPushNotificationConfig(@RequestBody params: TaskIdParams): ResponseEntity<JSONRPCResponse> {
        // Dummy: return a static config
        val config = TaskPushNotificationConfig(
            taskId = params.id,
            pushNotificationConfig = PushNotificationConfig(
                url = "https://client/notify",
                token = "demo-token",
                authentication = PushNotificationAuthenticationInfo(listOf("Bearer"), credentials = "secret")
            )
        )
        val result = JSONRPCSuccessResponse(id = params.id, result = config)
        return ResponseEntity.ok(result)
    }
}
