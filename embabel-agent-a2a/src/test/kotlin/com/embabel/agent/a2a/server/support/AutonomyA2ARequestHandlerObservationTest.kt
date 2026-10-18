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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import com.embabel.agent.a2a.A2A_CONTEXT_ID_SPAN_KEY
import com.embabel.agent.a2a.A2A_CONTEXT_ID_KEY
import com.embabel.agent.a2a.A2A_MESSAGE_SEND
import com.embabel.agent.a2a.A2A_METHOD_KEY
import com.embabel.agent.a2a.A2A_METHOD_SEND_VALUE
import com.embabel.agent.a2a.A2A_TASK_ID_KEY
import com.embabel.agent.a2a.server.A2ARequestHandler
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import io.a2a.spec.*
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * Verifies that [AutonomyA2ARequestHandler] emits correctly tagged Micrometer observations
 * for each A2A JSON-RPC dispatch.
 *
 * ## How it works
 * [TestObservationRegistry] (from Micrometer's test compatibility kit, `micrometer-observation-test`)
 * is an in-memory [io.micrometer.observation.ObservationRegistry] that captures every observation started inside the
 * handler without requiring a real metrics backend. After [AutonomyA2ARequestHandler.handleJsonRpc]
 * returns, the TCK assertion API verifies:
 * - **observation name** — the metric name (e.g. `a2a.message.send`)
 * - **low-cardinality keys** — tags safe for metric dimensions because their value set is bounded
 *   (e.g. `a2a.method = "message/send"`); these are exported to both metrics and traces
 * - **high-cardinality keys** — tags with unbounded values such as IDs; exported to traces/spans
 *   only, never to metrics, to avoid cardinality explosion
 *
 * ## Why no named agent (e.g. AgentFour) is needed
 * The handler delegates to [Autonomy.chooseAndRunAgent], which returns an
 * [com.embabel.agent.api.common.autonomy.AgentProcessExecution]. This test does not care which
 * agent was selected — it only asserts that observation tags are populated from the message's
 * `taskId` and `contextId` fields. A generic mock satisfies the handler's minimal
 * post-execution needs.
 */
class AutonomyA2ARequestHandlerObservationTest {

    private val autonomy: Autonomy = mockk()
    private val agenticEventListener: AgenticEventListener = mockk()
    private val streamingHandler: A2AStreamingHandler = mockk()

    private lateinit var registry: TestObservationRegistry
    private lateinit var handler: AutonomyA2ARequestHandler

    @BeforeEach
    fun setup() {
        every { autonomy.agentPlatform } returns mockk<AgentPlatform>(relaxed = true)
        every { autonomy.chooseAndRunAgent(any(), any()) } returns mockk(relaxed = true)
        justRun { agenticEventListener.onPlatformEvent(any()) }
        justRun { streamingHandler.sendStreamEvent(any(), any(), any()) }
        justRun { streamingHandler.closeStream(any()) }
        registry = TestObservationRegistry.create()
        handler = AutonomyA2ARequestHandler(autonomy, agenticEventListener, streamingHandler, registry)
    }

    @Test
    fun `handleJsonRpc creates a2a message send observation with contextId and taskId attributes`() {
        val taskId = UUID.randomUUID().toString()
        val contextId = "ctx-test-123"
        handler.handleJsonRpc(buildSendMessageRequest(taskId, contextId))

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(A2A_MESSAGE_SEND)
            .that()
            .hasLowCardinalityKeyValue(A2A_METHOD_KEY, A2A_METHOD_SEND_VALUE)
            .hasHighCardinalityKeyValue(A2A_TASK_ID_KEY, taskId)
            .hasHighCardinalityKeyValue(A2A_CONTEXT_ID_SPAN_KEY, contextId)
    }

    @Test
    fun `handleJsonRpc generates contextId and taskId when message has none`() {
        handler.handleJsonRpc(buildSendMessageRequest(taskId = null, contextId = null))

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(A2A_MESSAGE_SEND)
    }

    @Test
    fun `handleJsonRpc records observation error and returns error response when autonomy throws`() {
        every { autonomy.chooseAndRunAgent(any(), any()) } throws RuntimeException("agent failure")

        val handlerLogger = LoggerFactory.getLogger(A2ARequestHandler::class.java) as LogbackLogger
        val originalLevel = handlerLogger.level
        handlerLogger.level = Level.OFF

        val response = try {
            handler.handleJsonRpc(buildSendMessageRequest("task-1", "ctx-1"))
        } finally {
            handlerLogger.level = originalLevel
        }

        assertInstanceOf(JSONRPCErrorResponse::class.java, response)
        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(A2A_MESSAGE_SEND)
    }

    @Test
    fun `handleJsonRpcStream delegates to streamingHandler and returns SseEmitter`() {
        every { streamingHandler.createStream(any(), any(), any()) } returns SseEmitter()

        val builder = Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.USER)
            .parts(listOf(TextPart("stream intent")))
        val params = MessageSendParams.Builder().message(builder.build()).build()
        val request = SendStreamingMessageRequest(UUID.randomUUID().toString(), params)

        val emitter = handler.handleJsonRpcStream(request)

        assertInstanceOf(SseEmitter::class.java, emitter)
    }

    @Test
    fun `handleMessageStream restores contextId in OTel context on virtual thread`() {
        val contextId = "ctx-stream-test"
        var capturedContextId: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        every { streamingHandler.createStream(any(), any(), any()) } returns SseEmitter()
        every { autonomy.chooseAndRunAgent(any(), any()) } answers {
            // Capture the key value as seen from inside the virtual thread
            capturedContextId = Context.current().get(A2A_CONTEXT_ID_KEY)
            latch.countDown()
            mockk(relaxed = true)
        }

        val builder = Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.USER)
            .contextId(contextId)
            .parts(listOf(TextPart("stream intent")))
        val params = MessageSendParams.Builder().message(builder.build()).build()
        val request = SendStreamingMessageRequest(UUID.randomUUID().toString(), params)

        handler.handleMessageStream(request)
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(contextId, capturedContextId,
            "A2A_CONTEXT_ID_KEY must be visible on the virtual thread during streaming execution")
    }

    @Test
    fun `handleJsonRpc stores contextId in OTel context key so downstream A2A calls can inherit it`() {
        val contextId = "ctx-chain-test"
        var capturedContextId: String? = null

        // Capture the value of A2A_CONTEXT_ID_KEY as seen by code running inside the handler
        // (simulates what EmbabelA2AClient.resolveContextId will do in Step 4).
        every { autonomy.chooseAndRunAgent(any(), any()) } answers {
            capturedContextId = Context.current().get(A2A_CONTEXT_ID_KEY)
            mockk(relaxed = true)
        }

        handler.handleJsonRpc(buildSendMessageRequest(taskId = null, contextId = contextId))

        assertEquals(contextId, capturedContextId,
            "contextId must be visible in OTel context during agent execution")
    }

    @Test
    fun `handleJsonRpc restores context after completion — key is absent outside handler`() {
        every { autonomy.chooseAndRunAgent(any(), any()) } returns mockk(relaxed = true)

        handler.handleJsonRpc(buildSendMessageRequest(taskId = null, contextId = "ctx-restore-test"))

        assertNull(Context.current().get(A2A_CONTEXT_ID_KEY),
            "A2A_CONTEXT_ID_KEY must be absent after the handler returns — scope must be closed")
    }

    private fun buildSendMessageRequest(taskId: String?, contextId: String?): SendMessageRequest {
        var builder = Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.USER)
            .parts(listOf(TextPart("test intent")))
        if (taskId != null) builder = builder.taskId(taskId)
        if (contextId != null) builder = builder.contextId(contextId)
        val params = MessageSendParams.Builder()
            .message(builder.build())
            .build()
        return SendMessageRequest(UUID.randomUUID().toString(), params)
    }
}
