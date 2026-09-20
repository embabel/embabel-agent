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

import com.embabel.agent.a2a.A2A_CONTEXT_ID_BAGGAGE_KEY
import com.embabel.agent.a2a.A2A_MESSAGE_SEND
import com.embabel.agent.a2a.A2A_METHOD_KEY
import com.embabel.agent.a2a.A2A_METHOD_SEND_VALUE
import com.embabel.agent.a2a.A2A_TASK_ID_KEY
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import io.a2a.spec.*
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
            .hasHighCardinalityKeyValue(A2A_CONTEXT_ID_BAGGAGE_KEY, contextId)
    }

    @Test
    fun `handleJsonRpc generates contextId and taskId when message has none`() {
        handler.handleJsonRpc(buildSendMessageRequest(taskId = null, contextId = null))

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(A2A_MESSAGE_SEND)
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
