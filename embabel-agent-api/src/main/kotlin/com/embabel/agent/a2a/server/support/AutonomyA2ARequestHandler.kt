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

import com.embabel.agent.a2a.server.A2ARequestEvent
import com.embabel.agent.a2a.server.A2ARequestHandler
import com.embabel.agent.a2a.server.A2AResponseEvent
import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.event.AgenticEventListener
import io.a2a.spec.Artifact
import io.a2a.spec.CancelTaskRequest
import io.a2a.spec.CancelTaskResponse
import io.a2a.spec.DataPart
import io.a2a.spec.EventKind
import io.a2a.spec.GetTaskRequest
import io.a2a.spec.GetTaskResponse
import io.a2a.spec.JSONRPCErrorResponse
import io.a2a.spec.JSONRPCResponse
import io.a2a.spec.Message
import io.a2a.spec.MessageSendParams
import io.a2a.spec.NonStreamingJSONRPCRequest
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendMessageResponse
import io.a2a.spec.SendStreamingMessageRequest
import io.a2a.spec.StreamingJSONRPCRequest
import io.a2a.spec.Task
import io.a2a.spec.TaskIdParams
import io.a2a.spec.TaskNotFoundError
import io.a2a.spec.TaskQueryParams
import io.a2a.spec.TaskState
import io.a2a.spec.TaskStatus
import io.a2a.spec.TaskStatusUpdateEvent
import io.a2a.spec.TextPart
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDateTime
import java.util.UUID

/**
 * Handle A2A messages according to the A2A protocol.
 * Doesn't dictate mapping to URLs: a router or controller
 * in front of this class must handle that.
 */
@Service
@Profile("a2a")
class AutonomyA2ARequestHandler(
    private val autonomy: Autonomy,
    private val agenticEventListener: AgenticEventListener,
    private val streamingHandler: A2AStreamingHandler,
) : A2ARequestHandler {

    private val logger = LoggerFactory.getLogger(A2ARequestHandler::class.java)

    override fun handleJsonRpcStream(request: StreamingJSONRPCRequest<*>): SseEmitter {
        return when (request) {
            is SendStreamingMessageRequest -> handleMessageStream(request)
            else -> throw UnsupportedOperationException("Method ${request.method} is not supported for streaming")
        }
    }

    override fun handleJsonRpc(
        request: NonStreamingJSONRPCRequest<*>
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
        // TODO handle other message parts and handle errors
        val intent = params.message.parts.filterIsInstance<TextPart>().single().text
        logger.info("Handling message send request with intent: '{}'", intent)
        try {
            val result = autonomy.chooseAndRunAgent(
                intent = intent,
                processOptions = ProcessOptions(),
            )

            val task = Task.Builder()
                .id(ensureTaskId(params.message.taskId))
                .contextId(ensureContextId(params.message.contextId))
                .status(TaskStatus(TaskState.COMPLETED))
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

    fun handleMessageStream(request: SendStreamingMessageRequest): SseEmitter {
        val params = request.params
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()
        val emitter = streamingHandler.createStream(streamId)

        Thread.startVirtualThread {
            try {
                // Send initial status event
                streamingHandler.sendStreamEvent(
                    streamId, TaskStatusUpdateEvent.Builder()
                        .taskId(params.message.taskId)
                        .contextId(params.message.contextId)
                        .status(createWorkingTaskStatus(params, "Task started..."))
                        .build()
                )

                // Send the received message, if any
                params.message?.let { userMsg ->
                    streamingHandler.sendStreamEvent(streamId, userMsg)
                }

                val intent = params.message?.parts?.filterIsInstance<TextPart>()?.firstOrNull()?.text
                    ?: "Task ${params.message.taskId}"

                // Execute the task using autonomy service
                val result = autonomy.chooseAndRunAgent(
                    intent = intent,
                    processOptions = ProcessOptions()
                )
                logger.debug("Task execution result: {}", result)

                // Send intermediate status updates
                streamingHandler.sendStreamEvent(
                    streamId, TaskStatusUpdateEvent.Builder()
                        .taskId(params.message.taskId)
                        .contextId(ensureContextId(params.message.contextId))
                        .status(createWorkingTaskStatus(params, "Processing task..."))
                        .build()
                )

                // Send result
                val taskResult = Task.Builder()
                    .id(params.message.taskId)
                    .contextId("ctx_${UUID.randomUUID()}")
                    .status(createCompletedTaskStatus(params))
                    .history(listOfNotNull(params.message))
                    .artifacts(
                        listOf(
                            createResultArtifact(result, params.configuration?.acceptedOutputModes)
                        )
                    )
                    .metadata(null)
                    .build()
                streamingHandler.sendStreamEvent(streamId, taskResult)
            } catch (e: Exception) {
                logger.error("Streaming error", e)
                try {
                    streamingHandler.sendStreamEvent(
                        streamId, TaskStatusUpdateEvent.Builder()
                            .taskId(params.message.taskId)
                            .contextId(ensureContextId(params.message.contextId))
                            .status(createFailedTaskStatus(params, e))
                            .build()
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

    private fun handleTasksGet(
        request: GetTaskRequest,
        params: TaskQueryParams,
    ): GetTaskResponse {
        TODO()
    }

    private fun handleCancelTask(
        request: CancelTaskRequest,
        tip: TaskIdParams,
    ): CancelTaskResponse {
        TODO()
    }

    private fun createFailedTaskStatus(params: MessageSendParams, e: Exception): TaskStatus = TaskStatus(
        TaskState.FAILED,
        Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(listOf(TextPart("Error: ${e.message}")))
            .contextId(params.message.contextId)
            .taskId(params.message.taskId)
            .build(),
        LocalDateTime.now()
    )

    private fun createCompletedTaskStatus(
        params: MessageSendParams,
        textPart: String = "Task completed successfully"
    ): TaskStatus = TaskStatus(
        TaskState.COMPLETED,
        Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(listOf(TextPart(textPart)))
            .contextId(params.message.contextId)
            .taskId(params.message.taskId)
            .build(),
        LocalDateTime.now()
    )

    private fun createWorkingTaskStatus(
        params: MessageSendParams,
        textPart: String = "Working..."
    ): TaskStatus = TaskStatus(
        TaskState.WORKING,
        Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(listOf(TextPart(textPart)))
            .contextId(params.message.contextId)
            .taskId(params.message.taskId)
            .build(),
        LocalDateTime.now()
    )

    private fun ensureContextId(providedContextId: String?): String {
        return providedContextId ?: ("ctx_" + UUID.randomUUID().toString())
    }

    private fun ensureTaskId(providedTaskId: String?): String {
        return providedTaskId ?: UUID.randomUUID().toString()
    }

    private fun createResultArtifact(
        result: AgentProcessExecution,
        acceptedOutputModes: List<String>? = emptyList()
    ): Artifact {
        // TODO result should be based on the outputMode received in the "params.configuration.acceptedOutputModes"
        return Artifact.Builder()
            .artifactId(UUID.randomUUID().toString())
            .parts(
                listOf(
                    DataPart(mapOf("output" to result.output))
                )
            )
            .build()
    }
}

fun SendMessageRequest.successResponseWith(result: EventKind): SendMessageResponse {
    return SendMessageResponse(
        this.id,
        result,
    )
}
