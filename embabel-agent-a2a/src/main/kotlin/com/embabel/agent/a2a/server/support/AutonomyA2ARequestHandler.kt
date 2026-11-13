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
import com.embabel.agent.a2a.server.A2ARequestEvent
import com.embabel.agent.a2a.server.A2ARequestHandler
import com.embabel.agent.a2a.server.A2AResponseEvent
import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.library.HasContent
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.StreamingJSONRPCRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest
import org.a2aproject.sdk.spec.*
import org.a2aproject.sdk.spec.InternalError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.OffsetDateTime
import java.util.*
import javax.annotation.concurrent.ThreadSafe

/**
 * Handle A2A messages according to the A2A protocol.
 * Doesn't dictate mapping to URLs: a router or controller
 * in front of this class must handle that.
 */
@Service
@ThreadSafe
class AutonomyA2ARequestHandler(
    private val autonomy: Autonomy,
    private val agenticEventListener: AgenticEventListener,
    private val streamingHandler: A2AStreamingHandler,
    private val taskStateManager: TaskStateManager,
) : A2ARequestHandler {

    private val logger = LoggerFactory.getLogger(A2ARequestHandler::class.java)

    private val outputMapper = tools.jackson.databind.json.JsonMapper.builder()
        .addModule(tools.jackson.module.kotlin.kotlinModule())
        .findAndAddModules()
        .build()

    override fun handleJsonRpcStream(
        request: StreamingJSONRPCRequest<*>,
        protocolVersion: A2AProtocolVersion,
    ): SseEmitter {
        return when (request) {
            is SendStreamingMessageRequest -> handleMessageStream(request, protocolVersion)
            is SubscribeToTaskRequest -> handleTaskResubscribe(request, protocolVersion)
        }
    }

    override fun handleJsonRpc(
        request: NonStreamingJSONRPCRequest<*>,
    ): A2AResponse<*> {
        logger.info("Received JSONRPC message {}: {}", request.method, request::class.java.name)
        agenticEventListener.onPlatformEvent(
            A2ARequestEvent(
                agentPlatform = autonomy.agentPlatform,
                request = request,
            )
        )
        val result = when (request) {
            is SendMessageRequest -> {
                val messageSendParams = request.params
                handleMessageSend(request, messageSendParams)
            }

            is GetTaskRequest -> {
                val tqp = request.params
                handleTasksGet(request, tqp)
            }

            is CancelTaskRequest -> {
                val ctp = request.params
                handleCancelTask(request, ctp)
            }

            // The agent card declares no push notifications and no extended agent card
            is CreateTaskPushNotificationConfigRequest,
            is GetTaskPushNotificationConfigRequest,
            is ListTaskPushNotificationConfigsRequest,
            is DeleteTaskPushNotificationConfigRequest -> A2AErrorResponse(request.id, PushNotificationNotSupportedError())

            is GetExtendedAgentCardRequest, is ListTasksRequest -> A2AErrorResponse(request.id, UnsupportedOperationError())

            else -> {
                logger.warn("Unsupported method: {}", request.method)
                throw UnsupportedOperationException("Method ${request.method} is not supported")
            }
        }
        agenticEventListener.onPlatformEvent(
            A2AResponseEvent(
                agentPlatform = autonomy.agentPlatform,
                response = result,
            )
        )
        return result
    }

    private fun handleMessageSend(
        request: SendMessageRequest,
        params: MessageSendParams,
    ): A2AResponse<*> {
        val intent = intentOf(params.message)
        logger.info("Handling message send request with intent: '{}'", intent)
        val taskId = ensureTaskId(params.message.taskId)
        val contextId = ensureContextId(params.message.contextId)
        taskStateManager.registerTask(taskId, contextId)
        try {
            val result = autonomy.chooseAndRunAgent(
                intent = intent,
                processOptions = ProcessOptions(),
            )

            // Extract content for status message if output is HasContent
            val statusMessage = extractContentForDisplay(result)

            val task = Task.builder()
                .id(taskId)
                .contextId(contextId)
                .status(createCompletedTaskStatus(taskId, contextId, statusMessage))
                .history(listOfNotNull(params.message))
                .artifacts(
                    listOf(
                        createResultArtifact(result, params.configuration?.acceptedOutputModes)
                    )
                )
                .build()
            streamingHandler.publishTaskEvent(taskId, task)

            val jSONRPCResponse = request.successResponseWith(result = task)
            logger.info("Handled message send request, response={}", jSONRPCResponse)
            return jSONRPCResponse
        } catch (e: Throwable) {
            // Throwable, not Exception: an Error must still fail the task, or it stays working and its streams stay open
            logger.error("Error handling message send request", e)
            streamingHandler.publishTaskEvent(
                taskId,
                Task.builder()
                    .id(taskId)
                    .contextId(contextId)
                    .status(createFailedTaskStatus(taskId, contextId, e))
                    .history(listOfNotNull(params.message))
                    .build()
            )
            return A2AErrorResponse(request.id, InternalError("Internal error: ${e.message}"))
        }
    }

    fun handleMessageStream(
        request: SendStreamingMessageRequest,
        protocolVersion: A2AProtocolVersion = A2AProtocolVersion.V1_0,
    ): SseEmitter {
        val params = request.params
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()
        val taskId = ensureTaskId(params.message.taskId)
        val contextId = ensureContextId(params.message.contextId)

        val emitter = streamingHandler.createStream(streamId, taskId, contextId, protocolVersion)

        Thread.startVirtualThread {
            try {
                // Send initial status event
                streamingHandler.publishTaskEvent(
                    taskId,
                    TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(taskId, contextId, "Task started..."))
                        .build(),
                )

                val intent = intentOf(params.message).ifEmpty { "Task $taskId" }

                streamingHandler.publishTaskEvent(
                    taskId,
                    TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(taskId, contextId, "Processing task..."))
                        .build(),
                )

                // Execute the task using autonomy service
                val result = autonomy.chooseAndRunAgent(
                    intent = intent,
                    processOptions = ProcessOptions()
                )
                logger.debug("Task execution result: {}", result)

                // Extract content for status message if output is HasContent
                val statusMessage = extractContentForDisplay(result)

                // Send FINAL status update with content
                // Per A2A spec: final status-update with final=true is sufficient for completion
                // Task objects in streaming should only appear at the beginning, not at the end
                streamingHandler.publishTaskEvent(
                    taskId,
                    TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createCompletedTaskStatus(taskId, contextId, statusMessage))
                        .build(),
                )
            } catch (e: Throwable) {
                logger.error("Streaming error", e)
                try {
                    streamingHandler.publishTaskEvent(
                        taskId,
                        TaskStatusUpdateEvent.builder()
                            .taskId(taskId)
                            .contextId(contextId)
                            .status(createFailedTaskStatus(taskId, contextId, e))
                            .build(),
                    )
                } catch (sendError: Exception) {
                    logger.error("Error sending error event", sendError)
                }
            } finally {
                streamingHandler.closeStream(streamId)
            }
        }

        return emitter
    }

    /**
     * Handles task resubscription requests
     */
    fun handleTaskResubscribe(
        request: SubscribeToTaskRequest,
        protocolVersion: A2AProtocolVersion = A2AProtocolVersion.V1_0,
    ): SseEmitter {
        val params = request.params
        val taskId = params.id  // TaskIdParams.id contains the task identifier
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()

        logger.info("Handling task resubscribe request for taskId: {}, streamId: {}", taskId, streamId)

        // Errors before the stream opens are thrown, so the caller answers with a plain JSON-RPC error response
        if (taskId.isNullOrBlank()) {
            throw taskIdRequired()
        }
        return try {
            streamingHandler.resubscribeToTask(taskId, streamId, protocolVersion)
        } catch (e: IllegalArgumentException) {
            logger.warn("Task not found for resubscribe: {}", taskId)
            throw TaskNotFoundError()
        }
    }

    private fun handleTasksGet(
        request: GetTaskRequest,
        params: TaskQueryParams,
    ): GetTaskResponse {
        if (params.id.isNullOrBlank()) {
            return GetTaskResponse(request.id, taskIdRequired())
        }
        val task = taskStateManager.getTask(params.id)
            ?: return GetTaskResponse(request.id, TaskNotFoundError())
        return GetTaskResponse(request.id, task)
    }

    private fun handleCancelTask(
        request: CancelTaskRequest,
        ctp: CancelTaskParams,
    ): CancelTaskResponse {
        if (ctp.id.isNullOrBlank()) {
            return CancelTaskResponse(request.id, taskIdRequired())
        }
        // Agent processes run to completion, so no task can be canceled
        val error = if (taskStateManager.taskExists(ctp.id)) TaskNotCancelableError() else TaskNotFoundError()
        return CancelTaskResponse(request.id, error)
    }

    /**
     * The A2A SDK reads a request without params, or without a task id, as one with an empty task id
     */
    private fun taskIdRequired() = InvalidParamsError(null, "The task id is required", null)

    private fun createFailedTaskStatus(
        taskId: String,
        contextId: String,
        e: Throwable,
    ): TaskStatus = TaskStatus(
        TaskState.TASK_STATE_FAILED,
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT)
            .parts(listOf(TextPart("Error: ${e.message}")))
            .contextId(contextId)
            .taskId(taskId)
            .build(),
        OffsetDateTime.now()
    )

    private fun createCompletedTaskStatus(
        taskId: String,
        contextId: String,
        textPart: String = "Task completed successfully",
    ): TaskStatus = TaskStatus(
        TaskState.TASK_STATE_COMPLETED,
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT)
            .parts(listOf(TextPart(textPart)))
            .contextId(contextId)
            .taskId(taskId)
            .build(),
        OffsetDateTime.now()
    )

    private fun createWorkingTaskStatus(
        taskId: String,
        contextId: String,
        textPart: String = "Working...",
    ): TaskStatus = TaskStatus(
        TaskState.TASK_STATE_WORKING,
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT)
            .parts(listOf(TextPart(textPart)))
            .contextId(contextId)
            .taskId(taskId)
            .build(),
        OffsetDateTime.now()
    )

    /**
     * Joins every text part of the message, so no part of a multi-part message is lost
     */
    private fun intentOf(message: Message): String =
        message.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }

    private fun ensureContextId(providedContextId: String?): String {
        return providedContextId ?: ("ctx_" + UUID.randomUUID().toString())
    }

    private fun ensureTaskId(providedTaskId: String?): String {
        return providedTaskId ?: UUID.randomUUID().toString()
    }

    /**
     * Extracts content from AgentProcessExecution for display in status messages.
     * If output implements HasContent, returns the content field.
     * Otherwise, returns a generic completion message.
     */
    private fun extractContentForDisplay(result: AgentProcessExecution): String {
        val output = result.output
        return if (output is HasContent) {
            output.content
        } else {
            "Task completed successfully"
        }
    }

    internal fun createResultArtifact(
        result: AgentProcessExecution,
        acceptedOutputModes: List<String>? = emptyList(),
    ): Artifact {
        val parts = buildList<Part<*>> {
            val output = result.output

            // The A2A wire format keeps only maps, lists, strings, numbers and booleans in a DataPart,
            // so convert the output to those with Jackson
            val outputValue = outputMapper.convertValue(output, Any::class.java)

            // Check if output implements HasContent
            if (output is HasContent) {
                // Extract content and add as TextPart for end-user visibility
                add(TextPart(output.content))

                // The DataPart carries the object without the content field
                @Suppress("UNCHECKED_CAST")
                (outputValue as MutableMap<String, Any?>).remove("content")
            }
            add(DataPart(mapOf("output" to outputValue)))
        }

        return Artifact.builder()
            .artifactId(UUID.randomUUID().toString())
            .parts(parts)
            .build()
    }
}

fun SendMessageRequest.successResponseWith(result: EventKind): SendMessageResponse {
    return SendMessageResponse(
        this.id,
        result,
    )
}
