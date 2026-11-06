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
package com.embabel.agent.a2a.client

import com.embabel.agent.a2a.A2A_CLIENT_SEND
import com.embabel.agent.a2a.A2A_CONTEXT_ID_SPAN_KEY
import com.embabel.agent.a2a.A2A_TASK_ID_KEY
import com.embabel.agent.a2a.client.A2AException
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.spec.Message
import io.a2a.spec.TextPart
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

/**
 * Verifies that [EmbabelA2AClient.sendMessage] emits an [A2A_CLIENT_SEND] observation
 * tagged with contextId and taskId.
 *
 * The observation tags are populated before the HTTP call is made, so they are verifiable
 * even when the call fails (no running server). The success path is covered by
 * [com.embabel.agent.a2a.multi.A2AMultiAgentIntegrationTest].
 */
@ExtendWith(MockitoExtension::class)
class EmbabelA2AClientObservationTest {

    @Mock
    private lateinit var httpClientFactory: A2AHttpClientFactory

    private fun buildMessage(taskId: String?, contextId: String?): Message =
        Message.Builder()
            .role(Message.Role.USER)
            .messageId(UUID.randomUUID().toString())
            .apply { if (taskId != null) taskId(taskId) }
            .apply { if (contextId != null) contextId(contextId) }
            .parts(listOf(TextPart("hello")))
            .build()

    @Test
    fun `sendMessage emits a2a client send observation tagged with contextId and taskId`() {
        val registry = TestObservationRegistry.create()
        val taskId = UUID.randomUUID().toString()
        val contextId = "ctx-obs-test"

        val client = EmbabelA2AClient(
            httpClientFactory = httpClientFactory,
            objectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            observationRegistry = registry,
        )

        // No running server — call will fail on card fetch. Observation tags are set
        // before the HTTP call, so they are captured regardless of outcome.
        runCatching { client.sendMessage("http://localhost:0/agent", buildMessage(taskId, contextId)) }

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(A2A_CLIENT_SEND)
            .that()
            .hasHighCardinalityKeyValue(A2A_CONTEXT_ID_SPAN_KEY, contextId)
            .hasHighCardinalityKeyValue(A2A_TASK_ID_KEY, taskId)
            .hasError()
    }

    @Test
    fun `sendMessage observation uses empty string when contextId and taskId are absent`() {
        val registry = TestObservationRegistry.create()

        val client = EmbabelA2AClient(
            httpClientFactory = httpClientFactory,
            objectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            observationRegistry = registry,
        )

        runCatching { client.sendMessage("http://localhost:0/agent", buildMessage(null, null)) }

        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(A2A_CLIENT_SEND)
            .that()
            .hasHighCardinalityKeyValue(A2A_CONTEXT_ID_SPAN_KEY, "")
            .hasHighCardinalityKeyValue(A2A_TASK_ID_KEY, "")
    }
}
