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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.OffsetDateTime
import java.util.*

/**
 * Handle A2A messages according to the A2A protocol.
 * Doesn't dictate mapping to URLs: a router or controller
 * in front of this class must handle that.
 */
@Service
class AutonomyA2ARequestHandler(
    private val autonomy: Autonomy,
    private val agenticEventListener: AgenticEventListener,
    private val streamingHandler: A2AStreamingHandler,
    private val taskStore: InMemoryTaskStore,
) : A2ARequestHandler {

    private val logger = LoggerFactory.getLogger(A2ARequestHandler::class.java)

    override fun handleJsonRpcStream(request: StreamingJSONRPCRequest<*>): SseEmitter {
        return when (request) {
            is SendStreamingMessageRequest -> handleMessageStream(request)
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

/**
 * Handles a [SendMessageRequest] ("message/send"), the core A2A operation (v0.3.0 §7.1).
 *
 * @param request the parsed JSON-RPC request, used to echo the request ID
 * @param params the message and optional [SendMessageParams.configuration]
 * @return a [SendMessageResponse] wrapping the completed [Task] on success,
 *         or a [JSONRPCErrorResponse] if agent execution fails
 */
    private fun handleMessageSend(
        request: SendMessageRequest,
        params: MessageSendParams,
    ): JSONRPCResponse<*> {
        // TODO handle other message parts and handle errors
        val intent = params.message.parts.filterIsInstance<TextPart>().single().text
        logger.info("Handling message send request with intent: '{}'", intent)
        try {
            val result = autonomy.chooseAndRunAgent(
                intent = intent,
                processOptions = ProcessOptions(),
            )

            // Extract content for status message if output is HasContent
            val statusMessage = extractContentForDisplay(result)

            val taskId = ensureTaskId(params.message.taskId)
            val existing = taskStore.get(taskId)

            val task = Task.Builder()
                .id(ensureTaskId(params.message.taskId))
                .contextId(ensureContextId(params.message.contextId))
                .status(createCompletedTaskStatus(params, statusMessage))
                // append to prior history on follow-ups; [message] on first send
                .history((existing?.history ?: emptyList()) + params.message)
                .artifacts(
                        listOf(createResultArtifact(result, params.configuration?.acceptedOutputModes))
                )
                .build()
            taskStore.save(task)
            val jSONRPCResponse = request.successResponseWith(result = task)
            logger.info("Handled message send request, response={}", jSONRPCResponse)
            return jSONRPCResponse
        } catch (e: Exception) {
            logger.error("Error handling message send request", e)
            // TODO other kinds of errors
            return JSONRPCErrorResponse(
                ensureTaskId(params.message.taskId),
                TaskNotFoundError(
                    null,
                    "Internal error: ${e.message}",
                    e.stackTraceToString()
                )
            )
        }
    }

/**
 * Handles a [SendStreamingMessageRequest] ("message/stream"), the streaming variant of
 * [message/send][handleMessageSend] (A2A v0.3.0 §7.2).
 *
 * @param request the parsed JSON-RPC request carrying the message and optional
 *        [MessageSendParams.configuration]
 * @return an [SseEmitter] streaming the task's status events; errors during execution
 *         are reported as a FAILED status-update event on the stream rather than a
 *         JSON-RPC error envelope, since the HTTP 200 response has already committed
 */
fun handleMessageStream(request: SendStreamingMessageRequest): SseEmitter {
        val params = request.params
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()
        val taskId = ensureTaskId(params.message.taskId)
        val contextId = ensureContextId(params.message.contextId)

        val emitter = streamingHandler.createStream(streamId, taskId, contextId)

        Thread.startVirtualThread {
            try {
                // Send initial status event
                streamingHandler.sendStreamEvent(
                    streamId,
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(params, "Task started..."))
                        .build(),
                    taskId
                )

                val intent = params.message?.parts?.filterIsInstance<TextPart>()?.firstOrNull()?.text
                    ?: "Task $taskId"

                // Execute the task using autonomy service
                val result = autonomy.chooseAndRunAgent(
                    intent = intent,
                    processOptions = ProcessOptions()
                )
                logger.debug("Task execution result: {}", result)

                // Send intermediate status updates.
                streamingHandler.sendStreamEvent(
                    streamId,
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(params, "Processing task..."))
                        .build(),
                    taskId
                )

                // Extract content for status message if output is HasContent.
                val statusMessage = extractContentForDisplay(result)

                // Send FINAL status update with content.
                // Per A2A spec: final status-update with final=true is sufficient for completion.
                // Task objects in streaming should only appear at the beginning, not at the end.
                streamingHandler.sendStreamEvent(
                    streamId,
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createCompletedTaskStatus(params, statusMessage))
                        .isFinal(true)
                        .build(),
                    taskId
                )
            } catch (e: Exception) {
                logger.error("Streaming error", e)
                try {
                    streamingHandler.sendStreamEvent(
                        streamId,
                        TaskStatusUpdateEvent.Builder()
                            .taskId(taskId)
                            .contextId(contextId)
                            .status(createFailedTaskStatus(params, e))
                            .build(),
                        taskId
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

    override fun handleTaskResubscription(request: TaskResubscriptionRequest): SseEmitter {
        val taskId = request.params.id
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()
        logger.info("Handling task resubscribe request for taskId: {}, streamId: {}", taskId, streamId)
        return try {
            streamingHandler.resubscribeToTask(taskId, streamId)
        } catch (e: IllegalArgumentException) {
            logger.error("Task not found: {}", taskId, e)
            SseEmitter(Long.MAX_VALUE).also { it.completeWithError(e) }
        } catch (e: Exception) {
            logger.error("Error resubscribing to task: {}", taskId, e)
            SseEmitter(Long.MAX_VALUE).also { it.completeWithError(e) }
        }
    }

    /**
     * Handles a [GetTaskRequest] ("tasks/get") by returning the current state of the
     * task identified by [TaskQueryParams.id], together with its status and message history.
     *
     * Per A2A v0.3.0 §7.3, all implementations MUST support task retrieval by ID.
     *
     * @param request the parsed JSON-RPC request, used to echo the request ID
     * @param params the task query parameters: task ID, history length, and metadata
     * @return a [GetTaskResponse] whose result is the requested [Task]
     * @throws TaskNotFoundError if no task with the given ID is known to this server
     */
    private fun handleTasksGet(
        request: GetTaskRequest,
        params: TaskQueryParams,
    ): GetTaskResponse {
        val task = taskStore.get(params.id)
            ?: throw TaskNotFoundError()

        // historyLength=0 means "no limit"; >0 takes the last N entries
        val trimmed = if (params.historyLength > 0 && task.history != null)
            Task.Builder(task)
                .history(task.history!!.takeLast(params.historyLength))
                .build()
        else
            task

        return GetTaskResponse(request.id, trimmed)
    }

    /**
     * Handles a [CancelTaskRequest] ("tasks/cancel") by moving the identified task
     * to the terminal [TaskState.CANCELED] state, persisting the update, and
     * returning the canceled [Task].
     *
     * Per A2A v0.3.0 §7.4, all implementations MUST support task cancellation.
     *
     * @param request the parsed JSON-RPC request, used to echo the request ID
     * @param tip the [TaskIdParams] carrying the ID of the task to cancel
     * @return a [CancelTaskResponse] whose result is the canceled [Task]
     * @throws TaskNotFoundError if no task with the given ID is known to this server
     */
    private fun handleCancelTask(
        request: CancelTaskRequest,
        tip: TaskIdParams,
    ): CancelTaskResponse {
        val task = taskStore.get(tip.id)
            ?: throw TaskNotFoundError()

        if (task.status.state == TaskState.CANCELED) {
            throw TaskNotCancelableError()
        }

        val canceled = Task.Builder(task)
            .status(TaskStatus(TaskState.CANCELED))
            .build()
        taskStore.save(canceled)

        return CancelTaskResponse(request.id, canceled)
    }

    private fun createFailedTaskStatus(
        params: MessageSendParams,
        e: Exception,
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
        // TODO result should be based on the outputMode received in the "params.configuration.acceptedOutputModes"

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
