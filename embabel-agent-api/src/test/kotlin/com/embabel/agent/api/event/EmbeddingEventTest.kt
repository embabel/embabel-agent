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
package com.embabel.agent.api.event

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Usage
import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration

class EmbeddingEventTest {

    private val metadata = EmbeddingServiceMetadata(
        name = "test-model",
        provider = "test-provider",
        pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
    )

    @Test
    fun `EmbeddingRequestEvent exposes metadata, inputs and id`() {
        val event = EmbeddingRequestEvent(metadata, listOf("hello", "world"), "id-1")
        assertSame(metadata, event.embeddingMetadata)
        assertEquals(listOf("hello", "world"), event.inputs)
        assertEquals("id-1", event.id)
    }

    @Test
    fun `EmbeddingRequestEvent responseEvent links to its request`() {
        val request = EmbeddingRequestEvent(metadata, listOf("a"), "id-1")
        val usage = Usage(promptTokens = 5, completionTokens = 0, nativeUsage = null)
        val responseEvent = request.responseEvent(usage, Duration.ofMillis(10))
        assertSame(request, responseEvent.request)
        assertSame(usage, responseEvent.usage)
        assertEquals(Duration.ofMillis(10), responseEvent.runningTime)
    }

    @Test
    fun `EmbeddingResponseEvent delegates metadata, inputs and id to its request`() {
        val request = EmbeddingRequestEvent(metadata, listOf("a", "b"), "id-2")
        val responseEvent = request.responseEvent(
            Usage(7, 0, null),
            Duration.ofMillis(5),
        )
        assertSame(metadata, responseEvent.embeddingMetadata)
        assertEquals(listOf("a", "b"), responseEvent.inputs)
        assertEquals("id-2", responseEvent.id)
    }

    @Test
    fun `AgentProcessEmbeddingEvent wraps an EmbeddingEvent`() {
        val agentProcess = mock(AgentProcess::class.java)
        val request = EmbeddingRequestEvent(metadata, listOf("x"), "id-3")
        val wrapped = AgentProcessEmbeddingEvent(agentProcess, request)
        assertSame(request, wrapped.embeddingEvent)
        assertSame(agentProcess, wrapped.agentProcess)
    }

    @Test
    fun `EmbeddingEventListener NOOP plus listener returns the listener unchanged`() {
        val custom = EmbeddingEventListener { }
        assertSame(custom, EmbeddingEventListener.NOOP + custom)
        assertSame(custom, custom + EmbeddingEventListener.NOOP)
    }

    @Test
    fun `EmbeddingEventListener combines multiple listeners and forwards to all`() {
        val received1 = mutableListOf<EmbeddingEvent>()
        val received2 = mutableListOf<EmbeddingEvent>()
        val combined = EmbeddingEventListener { received1 += it } + EmbeddingEventListener { received2 += it }
        val event = EmbeddingRequestEvent(metadata, listOf("z"), "id-4")
        combined.onEmbeddingEvent(event)
        assertEquals(listOf<EmbeddingEvent>(event), received1)
        assertEquals(listOf<EmbeddingEvent>(event), received2)
    }

    @Test
    fun `EmbeddingEventListener continues after a throwing listener`() {
        val received = mutableListOf<EmbeddingEvent>()
        val combined = EmbeddingEventListener { error("boom") } +
            EmbeddingEventListener { received += it }
        val event = EmbeddingRequestEvent(metadata, listOf("y"), "id-5")
        combined.onEmbeddingEvent(event)
        assertEquals(listOf<EmbeddingEvent>(event), received)
    }
}
