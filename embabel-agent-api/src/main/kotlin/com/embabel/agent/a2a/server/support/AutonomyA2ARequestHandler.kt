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
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.event.AgenticEventListener
import com.fasterxml.jackson.databind.ObjectMapper
import io.a2a.spec.Artifact
import io.a2a.spec.DataPart
import io.a2a.spec.EventKind
import io.a2a.spec.JSONRPCError
import io.a2a.spec.JSONRPCErrorResponse
import io.a2a.spec.JSONRPCRequest
import io.a2a.spec.JSONRPCResponse
import io.a2a.spec.Message
import io.a2a.spec.MessageSendParams
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendMessageResponse
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
import java.util.*

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
    private val objectMapper: ObjectMapper,
) : A2ARequestHandler {

    private val logger = LoggerFactory.getLogger(A2ARequestHandler::class.java)

    override fun handleJsonRpcStream(request: JSONRPCRequest<Any>): SseEmitter {
        return when (request.method) {
            "message/stream" -> handleMessageStream(request)
            else -> throw UnsupportedOperationException("Method ${request.method} is not supported for streaming")
        }
    }

    override fun handleJsonRpc(
        request: JSONRPCRequest<Any>
    ): JSONRPCResponse<Any> {
        logger.info("Received JSONRPC message {}: {}", request.method, request)
        agenticEventListener.onPlatformEvent(
            A2ARequestEvent(
                agentPlatform = autonomy.agentPlatform,
                request = request,
            )
        )
        val result = when (request.method) {
            "message/send" -> {
                val messageSendParams = objectMapper.convertValue(request.params, MessageSendParams::class.java)
                handleMessageSend(request, messageSendParams) as JSONRPCResponse<Any>
            }

            "message/list" -> {
                TODO("Listing messages is not supported")
            }

            "message/pending" -> {
                TODO("pending messages is not supported")
            }

            "conversation/list" -> {
                TODO("Listing conversations is not supported")
            }

            "task/list" -> {
                val tqp = objectMapper.convertValue(request.params, TaskQueryParams::class.java)
                handleTasksGet(
                    request, tqp,
                )
            }

            "tasks/cancel" -> {
                val tip = objectMapper.convertValue(request.params, TaskIdParams::class.java)
                handleCancelTask(
                    request, tip,
                )
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
    ): JSONRPCResponse<out Any> {
        // TODO handle other message parts and handle errors
        val intent = params.message.parts.filterIsInstance<TextPart>().single().text
        logger.info("Handling message send request with intent: '{}'", intent)
        try {
            val dynamicExecutionResult = autonomy.chooseAndRunAgent(
                intent = intent,
                processOptions = ProcessOptions(),
            )
            val task = Task.Builder()
                .id(params.message.taskId ?: UUID.randomUUID().toString())
                .contextId(params.message.contextId ?: ("ctx_" + UUID.randomUUID().toString()))
                .status(TaskStatus(TaskState.COMPLETED))
                .history(listOfNotNull(params.message))
                .artifacts(
                    listOf(
                        Artifact.Builder()
                            .artifactId(UUID.randomUUID().toString())
                            .parts(
                                listOf(
                                    DataPart(mapOf("output" to dynamicExecutionResult.output))
                                )
                            )
                            .build()
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
                params.message.taskId ?: UUID.randomUUID().toString(),
                TaskNotFoundError(
                    null,
                   "Internal error: ${e.message}",
                    e.stackTraceToString()
                )
            )
        }
    }

    fun handleMessageStream(request: JSONRPCRequest<Any>): SseEmitter {
        val params = objectMapper.convertValue(request.params, MessageSendParams::class.java)
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()
        val emitter = streamingHandler.createStream(streamId)

        Thread.startVirtualThread {
            try {
                // Send initial status event
                val message = Message.Builder()
                    .messageId(UUID.randomUUID().toString())
                    .role(Message.Role.AGENT) // TODO? before it was system
                    .parts(listOf(TextPart("Task started...")))
                    .contextId(params.message.contextId)
                    .taskId(params.message.taskId)
                    .build()
                val statusEvent = TaskStatusUpdateEvent.Builder()
                    .taskId(params.message.taskId)
                    .contextId(params.message.contextId)
                    .status(TaskStatus(TaskState.WORKING, message, LocalDateTime.now()))
                    .build()
                streamingHandler.sendStreamEvent(streamId, statusEvent)

                // Send the received message, if any
                params.message?.let { userMsg ->
                    streamingHandler.sendStreamEvent(streamId, MessageResult(message = userMsg))
                }

                val intent = params.message?.parts?.filterIsInstance<TextPart>()?.firstOrNull()?.text
                    ?: "Task ${params.message.taskId}"

                // Execute the task using autonomy service
                val result = autonomy.chooseAndRunAgent(
                    intent = intent,
                    processOptions = ProcessOptions()
                )

                // Send intermediate status updates
                streamingHandler.sendStreamEvent(streamId, TaskStatusUpdateEvent(
                    taskId = params.taskId,
                    contextId = "ctx_${UUID.randomUUID()}",
                    status = TaskStatus(
                        state = TaskState.working,
                        message = Message(
                            role = "system",
                            parts = listOf(TextPart("Processing task...")),
                            messageId = UUID.randomUUID().toString(),
                            taskId = params.taskId
                        )
                    )
                ))

                // Send result
                val taskResult = TaskResult(
                    task = Task(
                        id = params.taskId,
                        contextId = "ctx_${UUID.randomUUID()}",
                        status = TaskStatus(
                            state = TaskState.completed,
                            message = Message(
                                role = "system",
                                parts = listOf(TextPart("Task completed successfully")),
                                messageId = UUID.randomUUID().toString(),
                                taskId = params.taskId
                            )
                        ),
                        history = listOfNotNull(params.message),
                        artifacts = listOf(
                            Artifact(
                                parts = listOf(DataPart(data = mapOf("output" to result.output)))
                            )
                        ),
                        metadata = null
                    )
                )
                streamingHandler.sendStreamEvent(streamId, taskResult)
                streamingHandler.closeStream(streamId)
            } catch (e: Exception) {
                logger.error("Streaming error", e)
                try {
                    streamingHandler.sendStreamEvent(streamId, TaskStatusUpdateEvent(
                        taskId = params.taskId,
                        contextId = "ctx_${UUID.randomUUID()}",
                        status = TaskStatus(
                            state = TaskState.failed,
                            message = Message(
                                role = "system",
                                parts = listOf(TextPart("Error: ${e.message}")),
                                messageId = UUID.randomUUID().toString(),
                                taskId = params.taskId
                            )
                        )
                    ))
                } catch (sendError: Exception) {
                    logger.error("Error sending error event", sendError)
                }
                streamingHandler.closeStream(streamId)
            }
        }

        return emitter
    }

    private fun handleTasksGet(
        request: JSONRPCRequest<Any>,
        params: TaskQueryParams,
    ): JSONRPCResponse<Any> {
        TODO()
    }

    private fun handleCancelTask(
        request: JSONRPCRequest<Any>,
        tip: TaskIdParams,
    ): JSONRPCResponse<Any> {
        TODO()
    }


}

fun SendMessageRequest.successResponseWith(result: EventKind): SendMessageResponse {
    return SendMessageResponse(
        this.id,
        result,
    )
}
