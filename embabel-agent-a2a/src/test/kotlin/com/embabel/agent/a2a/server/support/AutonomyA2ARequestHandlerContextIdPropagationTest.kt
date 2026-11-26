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

import com.embabel.agent.a2a.A2AContextIdThreadLocalAccessor
import com.embabel.agent.a2a.client.EmbabelA2AClient
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.spec.Message
import io.a2a.spec.MessageSendParams
import io.a2a.spec.SendMessageRequest
import io.a2a.spec.SendStreamingMessageRequest
import io.a2a.spec.TextPart
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.otel.bridge.OtelBaggageManager
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The inbound contextId must reach an agent's outbound A2A call when traces are exported, i.e. with
 * micrometer-tracing-bridge-otel in place. Shape of a real request: a server observation (Spring's
 * `http post /a2a`) is current, the handler runs, and the agent's work is nested in agent and action
 * observations (as `AbstractAgentProcess.run` / `executeAction` do) before the client resolves the contextId.
 */
class AutonomyA2ARequestHandlerContextIdPropagationTest {

    private val autonomy: Autonomy = mockk()
    private val agenticEventListener: AgenticEventListener = mockk()
    private val streamingHandler: A2AStreamingHandler = mockk()
    private val client = EmbabelA2AClient(mockk(relaxed = true), EmbabelObjectMapperHolder.createDefault())

    private lateinit var tracerProvider: SdkTracerProvider
    private lateinit var registry: ObservationRegistry
    private lateinit var handler: AutonomyA2ARequestHandler

    @BeforeEach
    fun setup() {
        tracerProvider = SdkTracerProvider.builder().build()
        val currentTraceContext = OtelCurrentTraceContext()
        val tracer = OtelTracer(
            tracerProvider.get("test"),
            currentTraceContext,
            {},
            OtelBaggageManager(currentTraceContext, emptyList(), emptyList()),
        )
        registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(DefaultTracingObservationHandler(tracer))

        every { autonomy.agentPlatform } returns mockk<AgentPlatform>(relaxed = true)
        justRun { agenticEventListener.onPlatformEvent(any()) }
        justRun { streamingHandler.sendStreamEvent(any(), any(), any()) }
        justRun { streamingHandler.closeStream(any()) }
        every { streamingHandler.createStream(any(), any(), any()) } returns SseEmitter()
        handler = AutonomyA2ARequestHandler(
            autonomy, agenticEventListener, streamingHandler, mockk<TaskStateManager>(relaxed = true), registry
        )
    }

    @AfterEach
    fun tearDown() {
        tracerProvider.close()
    }

    /** What the agent's outbound A2A call would send, resolved inside nested agent/action observations. */
    private fun outboundContextIdFromAgentAction(): String? {
        var resolved: String? = null
        Observation.createNotStarted("agent", registry).observe(Runnable {
            Observation.createNotStarted("action", registry).observe(Runnable {
                resolved = client.resolveContextId(null)
            })
        })
        return resolved
    }

    private fun <T> inServerObservation(block: () -> T): T {
        val server = Observation.createNotStarted("http.server.requests", registry).start()
        try {
            server.openScope().use { return block() }
        } finally {
            server.stop()
        }
    }

    @Nested
    inner class MessageSend {

        @Test
        fun `agent action outbound call inherits inbound contextId`() {
            var outbound: String? = null
            every { autonomy.chooseAndRunAgent(any(), any()) } answers {
                outbound = outboundContextIdFromAgentAction()
                mockk(relaxed = true)
            }

            inServerObservation { handler.handleJsonRpc(sendRequest("case-1")) }

            assertEquals("case-1", outbound)
        }

        @Test
        fun `contextId does not outlive the request`() {
            every { autonomy.chooseAndRunAgent(any(), any()) } returns mockk(relaxed = true)

            inServerObservation { handler.handleJsonRpc(sendRequest("case-1")) }

            assertNull(A2AContextIdThreadLocalAccessor.currentContextId())
            assertNull(client.resolveContextId(null))
        }
    }

    @Nested
    inner class MessageStream {

        @Test
        fun `agent action outbound call inherits inbound contextId`() {
            var outbound: String? = null
            val done = CountDownLatch(1)
            every { autonomy.chooseAndRunAgent(any(), any()) } answers {
                outbound = outboundContextIdFromAgentAction()
                done.countDown()
                mockk(relaxed = true)
            }

            inServerObservation { handler.handleMessageStream(streamRequest("case-2")) }

            assertTrue(done.await(5, TimeUnit.SECONDS), "agent did not run")
            assertEquals("case-2", outbound)
        }
    }

    private fun message(contextId: String) = Message.Builder()
        .messageId(UUID.randomUUID().toString())
        .role(Message.Role.USER)
        .contextId(contextId)
        .parts(listOf(TextPart("intent")))
        .build()

    private fun sendRequest(contextId: String) = SendMessageRequest(
        UUID.randomUUID().toString(),
        MessageSendParams.Builder().message(message(contextId)).build(),
    )

    private fun streamRequest(contextId: String) = SendStreamingMessageRequest(
        UUID.randomUUID().toString(),
        MessageSendParams.Builder().message(message(contextId)).build(),
    )
}
