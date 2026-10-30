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

import com.embabel.agent.a2a.A2A_CONTEXT_ID_SPAN_KEY
import com.embabel.agent.a2a.A2A_CONTEXT_ID_KEY
import com.embabel.agent.a2a.A2A_MESSAGE_SEND
import com.embabel.agent.a2a.A2A_MESSAGE_STREAM
import com.embabel.agent.a2a.A2A_METHOD_KEY
import com.embabel.agent.a2a.A2A_METHOD_SEND_VALUE
import com.embabel.agent.a2a.A2A_METHOD_STREAM_VALUE
import com.embabel.agent.a2a.A2A_TASK_ID_KEY
import com.embabel.agent.a2a.server.A2ARequestEvent
import com.embabel.agent.a2a.server.A2ARequestHandler
import com.embabel.agent.a2a.server.A2AResponseEvent
import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.ProcessOptions
import io.a2a.spec.*
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.opentelemetry.context.Context
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
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : A2ARequestHandler {

    private val logger = LoggerFactory.getLogger(A2ARequestHandler::class.java)

    override fun handleJsonRpcStream(request: StreamingJSONRPCRequest<*>): SseEmitter {
        return when (request) {
            is SendStreamingMessageRequest -> handleMessageStream(request)
            else -> throw UnsupportedOperationException("Method ${request.method} is not supported for streaming")
        }
    }

    /**
     * Handles streaming JSON-RPC requests that are not part of the standard SDK
     */
    fun handleCustomStreamingRequest(method: String, requestMap: Map<String, Any>, objectMapper: tools.jackson.databind.ObjectMapper): SseEmitter {
        return when (method) {
            TaskResubscriptionRequest.METHOD -> {
                val request = objectMapper.convertValue(requestMap, TaskResubscriptionRequest::class.java)
                handleTaskResubscribe(request)
            }
            else -> throw UnsupportedOperationException("Method $method is not supported for streaming")
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
        val taskId = ensureTaskId(params.message.taskId)
        val contextId = ensureContextId(params.message.contextId)
        // TODO handle other message parts and handle errors
        val intent = params.message.parts.filterIsInstance<TextPart>().single().text
        logger.info("Handling message send request with intent: '{}'", intent)

        // Two separate correlation mechanisms flow through every A2A call:
        //   traceparent (W3C)  — OTel injects this into every outbound HTTP call automatically,
        //                        including calls to LLM providers, via the instrumented RestClient.
        //   contextId          — application-level session identifier; carried inside the A2A
        //                        message body only.  NOT placed in OTel baggage because the
        //                        instrumented RestClient injects baggage as HTTP headers on every
        //                        outbound call — including to OpenAI, Anthropic, and other LLM
        //                        providers — sending an internal session ID to third parties.
        //                        Recorded here as a span tag so it is queryable in traces without
        //                        being forwarded further.
        // a2a.method is bounded ("message/send" | "message/stream") — low cardinality, safe as
        // a metric label and exported to both metrics and traces.
        // taskId and contextId are per-request UUIDs (unbounded) — high-cardinality keys are
        // exported to traces/spans only, never to metrics, avoiding cardinality explosion.
        val observation = Observation.createNotStarted(A2A_MESSAGE_SEND, observationRegistry)
            .lowCardinalityKeyValue(A2A_METHOD_KEY, A2A_METHOD_SEND_VALUE)
            .highCardinalityKeyValue(A2A_TASK_ID_KEY, taskId)
            .highCardinalityKeyValue(A2A_CONTEXT_ID_SPAN_KEY, contextId)
            .start()
        val observationScope = observation.openScope()
        // Store contextId in a JVM-local OTel context slot so EmbabelA2AClient can read it
        // when the agent makes outbound A2A calls — enabling transparent chain propagation
        // without requiring agent business code to thread contextId explicitly.
        // ContextKey is never serialised into HTTP headers, so it cannot leak to LLM providers.
        val contextScope = Context.current().with(A2A_CONTEXT_ID_KEY, contextId).makeCurrent()
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
                .artifacts(listOf(createResultArtifact(result, params.configuration?.acceptedOutputModes)))
                .build()

            val jSONRPCResponse = request.successResponseWith(result = task)
            logger.info("Handled message send request, response={}", jSONRPCResponse)
            return jSONRPCResponse
        } catch (e: Exception) {
            logger.error("Error handling message send request", e)
            observation.error(e)
            // TODO other kinds of errors
            return JSONRPCErrorResponse(
                taskId,
                TaskNotFoundError(null, "Internal error: ${e.message}", e.stackTraceToString())
            )
        } finally {
            contextScope.close()
            observationScope.close()
            observation.stop()
        }
    }

    fun handleMessageStream(request: SendStreamingMessageRequest): SseEmitter {
        val params = request.params
        val streamId = request.id?.toString() ?: UUID.randomUUID().toString()
        val taskId = ensureTaskId(params.message.taskId)
        val contextId = ensureContextId(params.message.contextId)

        val emitter = streamingHandler.createStream(streamId, taskId, contextId)

        val observation = Observation.createNotStarted(A2A_MESSAGE_STREAM, observationRegistry)
            .lowCardinalityKeyValue(A2A_METHOD_KEY, A2A_METHOD_STREAM_VALUE)
            .highCardinalityKeyValue(A2A_TASK_ID_KEY, taskId)
            .highCardinalityKeyValue(A2A_CONTEXT_ID_SPAN_KEY, contextId)
            .start()

        // Manually capture and restore OTel Context so A2A_CONTEXT_ID_KEY is visible to
        // EmbabelA2AClient when the agent makes outbound A2A calls during streaming.
        // Virtual threads do not inherit the parent's OTel Context automatically.
        val capturedContext = Context.current().with(A2A_CONTEXT_ID_KEY, contextId)

        Thread.startVirtualThread {
            // Restore the captured OTel Context on this virtual thread so A2A_CONTEXT_ID_KEY
            // is visible for the duration of the agent execution. Scope.close() in finally
            // pops it back, leaving the thread's context clean for reuse.
            val contextScope = capturedContext.makeCurrent()
            // openScope() makes a2a.message.stream the current observation on this thread so
            // agent execution spans register as its children in the trace hierarchy.
            val observationScope = observation.openScope()
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

                // Send intermediate status updates
                streamingHandler.sendStreamEvent(
                    streamId,
                    TaskStatusUpdateEvent.Builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(createWorkingTaskStatus(params, "Processing task..."))
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
                observation.error(e)
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
                observationScope.close()
                contextScope.close()
                observation.stop()
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
        tip: TaskIdParams,
    ): CancelTaskResponse {
        TODO()
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
