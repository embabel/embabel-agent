package com.embabel.agent.a2a.server

import com.embabel.agent.a2a.spec.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service

@Service
@Profile("a2a")
class A2AMessageHandler(
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(A2AMessageHandler::class.java)

    fun handleJsonRpc(
        request: JSONRPCRequest
    ): ResponseEntity<JSONRPCResponse> {
        logger.info("Received message: {}", request)
        return when (request.method) {
            "message/send" -> {
                val messageSendParams = objectMapper.convertValue(request.params, MessageSendParams::class.java)
                handleMessageSend(
                    MessageSendRequest(
                        id = request.id,
                        params = messageSendParams,
                    )
                )
            }

            "message/stream" -> {
                TODO("Streaming is not supported")
            }

            else -> {
                logger.warn("Unsupported method: {}", request.method)
                return ResponseEntity.badRequest().body(
                    JSONRPCErrorResponse(
                        id = request.id,
                        error = JSONRPCError(code = -32601, message = "Method not found")
                    )
                )
            }
        }
    }

    private fun handleMessageSend(request: MessageSendRequest): ResponseEntity<JSONRPCResponse> {
        val message = request.params.message
        val task = Task(
            id = message.taskId ?: "task-1",
            contextId = message.contextId ?: "ctx-1",
            status = TaskStatus(TaskState.COMPLETED),
            history = listOf(message),
            artifacts = listOf(
                Artifact(
                    parts = listOf(TextPart(text = "Echo: ${message.kind}")),
                )
            ),
            metadata = null,
        )
        val result = JSONRPCSuccessResponse(id = message.messageId, result = task)
        logger.info("Handled message send request: {}", result)
        return ResponseEntity.ok(result)
    }

}