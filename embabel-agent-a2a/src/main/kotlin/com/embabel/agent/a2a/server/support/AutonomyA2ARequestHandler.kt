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
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse
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
) : A2ARequestHandler {

    private val logger = LoggerFactory.getLogger(A2ARequestHandler::class.java)

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
            val contextId = ensureContextId(params.message.contextId)
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

            val jSONRPCResponse = request.successResponseWith(result = task)
            logger.info("Handled message send request, response={}", jSONRPCResponse)
            return jSONRPCResponse
        } catch (e: Exception) {
            logger.error("Error handling message send request", e)
            // TODO other kinds of errors
            return A2AErrorResponse(
                request.id,
                InternalError("Internal error: ${e.message}"),
            )
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
                streamingHandler.sendStreamEvent(
                    streamId,
                    TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(taskId, contextId, "Task started..."))
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

                // Send intermediate status updates
                streamingHandler.sendStreamEvent(
                    streamId,
                    TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(taskId, contextId, "Processing task..."))
                        .build(),
                    taskId
                )

                // Extract content for status message if output is HasContent
                val statusMessage = extractContentForDisplay(result)

                // Send FINAL status update with content
                // Per A2A spec: final status-update with final=true is sufficient for completion
                // Task objects in streaming should only appear at the beginning, not at the end
                streamingHandler.sendStreamEvent(
                    streamId,
                    TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createCompletedTaskStatus(taskId, contextId, statusMessage))
                        .build(),
                    taskId
                )
            } catch (e: Exception) {
                logger.error("Streaming error", e)
                try {
                    streamingHandler.sendStreamEvent(
                        streamId,
                        TaskStatusUpdateEvent.builder()
                            .taskId(taskId)
                            .contextId(contextId)
                            .status(createFailedTaskStatus(taskId, contextId, e))
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

        return try {
            streamingHandler.resubscribeToTask(taskId, streamId, protocolVersion)
        } catch (e: IllegalArgumentException) {
            logger.error("Task not found: {}", taskId, e)
            val emitter = SseEmitter(Long.MAX_VALUE)
            emitter.completeWithError(e)
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
        TODO()
    }

    private fun handleCancelTask(
        request: CancelTaskRequest,
        ctp: CancelTaskParams,
    ): CancelTaskResponse {
        TODO()
    }

    private fun createFailedTaskStatus(
        taskId: String,
        contextId: String,
        e: Exception,
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
        // TODO result should be based on the outputMode received in the "params.configuration.acceptedOutputModes"

        val parts = buildList<Part<*>> {
            val output = result.output

            // The A2A wire format keeps only maps, lists, strings, numbers and booleans in a DataPart,
            // so convert the output to those with Jackson
            val objectMapper = tools.jackson.databind.json.JsonMapper.builder()
                .addModule(tools.jackson.module.kotlin.kotlinModule())
                .findAndAddModules()
                .build()
            val outputValue = objectMapper.convertValue(output, Any::class.java)

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
