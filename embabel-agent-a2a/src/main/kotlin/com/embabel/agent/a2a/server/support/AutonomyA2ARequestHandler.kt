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

import com.embabel.agent.a2a.server.A2ARequestEvent
import com.embabel.agent.a2a.server.A2ARequestHandler
import com.embabel.agent.a2a.server.A2AResponseEvent
import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.ProcessOptions
import io.a2a.spec.*
import io.a2a.spec.InternalError
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
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

    override fun handleJsonRpcStream(request: StreamingJSONRPCRequest<*>): SseEmitter {
        return when (request) {
            is SendStreamingMessageRequest -> handleMessageStream(request)
            is TaskResubscriptionRequest -> handleTaskResubscribe(request)
            else -> throw UnsupportedOperationException("Method ${request.method} is not supported for streaming")
        }
    }

    override fun handleJsonRpc(
        request: NonStreamingJSONRPCRequest<*>,
    ): JSONRPCResponse<*> {
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
                val tip = request.params
                handleCancelTask(request, tip)
            }

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
    ): JSONRPCResponse<*> {
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

            val task = Task.Builder()
                .id(taskId)
                .contextId(contextId)
                .status(createCompletedTaskStatus(params, statusMessage))
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
                Task.Builder()
                    .id(taskId)
                    .contextId(contextId)
                    .status(createFailedTaskStatus(params, e))
                    .history(listOfNotNull(params.message))
                    .build()
            )
            return JSONRPCErrorResponse(request.id, InternalError("Internal error: ${e.message}"))
        }
    }

    fun handleMessageStream(request: SendStreamingMessageRequest): SseEmitter {
        val params = request.params
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()
        val taskId = ensureTaskId(params.message.taskId)
        val contextId = ensureContextId(params.message.contextId)

        val emitter = streamingHandler.createStream(streamId, taskId, contextId)

        Thread.startVirtualThread {
            try {
                // Send initial status event
                streamingHandler.publishTaskEvent(
                    taskId,
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(params, "Task started..."))
                        .build(),
                )

                val intent = intentOf(params.message).ifEmpty { "Task $taskId" }

                streamingHandler.publishTaskEvent(
                    taskId,
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(params, "Processing task..."))
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
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createCompletedTaskStatus(params, statusMessage))
                        .isFinal(true)
                        .build(),
                )
            } catch (e: Throwable) {
                logger.error("Streaming error", e)
                try {
                    streamingHandler.publishTaskEvent(
                        taskId,
                        TaskStatusUpdateEvent.Builder()
                            .taskId(taskId)
                            .contextId(contextId)
                            .status(createFailedTaskStatus(params, e))
                            .isFinal(true)
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
    fun handleTaskResubscribe(request: TaskResubscriptionRequest): SseEmitter {
        val params = request.params
        val taskId = params.id  // TaskIdParams.id contains the task identifier
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()

        logger.info("Handling task resubscribe request for taskId: {}, streamId: {}", taskId, streamId)

        return try {
            streamingHandler.resubscribeToTask(taskId, streamId)
        } catch (e: IllegalArgumentException) {
            logger.warn("Task not found for resubscribe: {}", taskId)
            val emitter = SseEmitter(Long.MAX_VALUE)
            emitter.send(
                SseEmitter.event()
                    .data(JSONRPCErrorResponse(request.id, TaskNotFoundError()), MediaType.APPLICATION_JSON)
            )
            emitter.complete()
            emitter
        } catch (e: Exception) {
            logger.error("Error resubscribing to task: {}", taskId, e)
            val emitter = SseEmitter(Long.MAX_VALUE)
            emitter.completeWithError(e)
            emitter
        }
    }

    private fun handleTasksGet(
        request: GetTaskRequest,
        params: TaskQueryParams,
    ): GetTaskResponse {
        val task = taskStateManager.getTask(params.id)
            ?: return GetTaskResponse(request.id, TaskNotFoundError())
        return GetTaskResponse(request.id, task)
    }

    private fun handleCancelTask(
        request: CancelTaskRequest,
        tip: TaskIdParams,
    ): CancelTaskResponse {
        // Agent processes run to completion, so no task can be canceled
        val error = if (taskStateManager.taskExists(tip.id)) TaskNotCancelableError() else TaskNotFoundError()
        return CancelTaskResponse(request.id, error)
    }

    private fun createFailedTaskStatus(
        params: MessageSendParams,
        e: Throwable,
    ): TaskStatus = TaskStatus(
        TaskState.FAILED,
        Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(listOf(TextPart("Error: ${e.message}")))
            .contextId(params.message.contextId)
            .taskId(params.message.taskId)
            .build(),
        OffsetDateTime.now()
    )

    private fun createCompletedTaskStatus(
        params: MessageSendParams,
        textPart: String = "Task completed successfully",
    ): TaskStatus = TaskStatus(
        TaskState.COMPLETED,
        Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(listOf(TextPart(textPart)))
            .contextId(params.message.contextId)
            .taskId(params.message.taskId)
            .build(),
        OffsetDateTime.now()
    )

    private fun createWorkingTaskStatus(
        params: MessageSendParams,
        textPart: String = "Working...",
    ): TaskStatus = TaskStatus(
        TaskState.WORKING,
        Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(listOf(TextPart(textPart)))
            .contextId(params.message.contextId)
            .taskId(params.message.taskId)
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
        return if (output is com.embabel.agent.domain.library.HasContent) {
            output.content
        } else {
            "Task completed successfully"
        }
    }

    private fun createResultArtifact(
        result: AgentProcessExecution,
        acceptedOutputModes: List<String>? = emptyList(),
    ): Artifact {
        val parts = buildList {
            val output = result.output

            // Check if output implements HasContent
            if (output is com.embabel.agent.domain.library.HasContent) {
                // Extract content and add as TextPart for end-user visibility
                add(TextPart(output.content))

                // Serialize the object without the content field for DataPart
                // Convert to map using Jackson, then remove the content field
                val objectMapper = tools.jackson.databind.json.JsonMapper.builder()
                    .addModule(tools.jackson.module.kotlin.kotlinModule())
                    .findAndAddModules()
                    .build()

                @Suppress("UNCHECKED_CAST")
                val outputMap = objectMapper.convertValue(output, Map::class.java) as MutableMap<String, Any?>
                outputMap.remove("content")

                add(DataPart(mapOf("output" to outputMap)))
            } else {
                // Standard behavior: serialize entire output in DataPart
                add(DataPart(mapOf("output" to output)))
            }
        }

        return Artifact.Builder()
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
