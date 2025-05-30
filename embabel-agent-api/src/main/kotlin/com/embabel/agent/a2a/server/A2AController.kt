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
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/a2a")
class A2AController {
    @GetMapping("/.well-known/agent.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun agentCard(): AgentCard =
        AgentCard(
            name = "Demo Agent",
            description = "A demo agent for the Embabel platform.",
            url = "https://localhost:8080/",
            provider = AgentProvider("Embabel", "https://embabel.com"),
            version = "0.1.0",
            documentationUrl = "https://embabel.com/docs",
            capabilities = AgentCapabilities(
                streaming = false,
                pushNotifications = false,
                stateTransitionHistory = false,
            ),
            securitySchemes = null,
            security = null,
            defaultInputModes = listOf("text/plain"),
            defaultOutputModes = listOf("text/plain"),
            skills = listOf(
                AgentSkill(
                    id = "echo",
                    name = "Echo",
                    description = "Echoes messages.",
                    tags = listOf("test"),
                    examples = listOf("Say hello!"),
                ),
            ),
            supportsAuthenticatedExtendedCard = false
        )

    // --- message/send ---
    @PostMapping(
        "/message/send",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun sendMessage(@RequestBody @Schema(description = "A2A message send params") params: MessageSendParams): ResponseEntity<JSONRPCResponse> {
        // Dummy implementation: echo back the message, create a dummy task
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

    // --- tasks/get ---
    @PostMapping(
        "/tasks/get",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getTask(@RequestBody params: TaskQueryParams): ResponseEntity<JSONRPCResponse> {
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

    // --- tasks/cancel ---
    @PostMapping(
        "/tasks/cancel",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun cancelTask(@RequestBody params: TaskIdParams): ResponseEntity<JSONRPCResponse> {
        // Dummy implementation: always successful
        val task = Task(
            id = params.id,
            contextId = "ctx-1",
            status = TaskStatus(TaskState.CANCELED),
            history = emptyList(),
            artifacts = emptyList(),
            metadata = null
        )
        val result = JSONRPCSuccessResponse(id = params.id, result = task)
        return ResponseEntity.ok(result)
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
