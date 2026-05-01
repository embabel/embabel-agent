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
package com.embabel.agent.spi.support

import com.embabel.agent.api.event.AgentProcessEmbeddingEvent
import com.embabel.agent.api.event.EmbeddingEvent
import com.embabel.agent.api.event.EmbeddingEventListener
import com.embabel.agent.api.event.EmbeddingRequestEvent
import com.embabel.agent.api.event.EmbeddingResponseEvent
import com.embabel.agent.spi.support.embedding.EmbeddingOperations
import com.embabel.agent.spi.support.springai.EmbeddingModelCallEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.ai.model.TokenCountEstimator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.embedding.EmbeddingResponseMetadata
import java.util.LinkedList

class EmbeddingOperationsTest {

    private lateinit var listener: EventSavingAgenticEventListener
    private lateinit var process: SimpleAgentProcess
    private lateinit var capturedEvents: MutableList<EmbeddingEvent>
    private lateinit var embeddingListener: EmbeddingEventListener

    private val pricing = PricingModel.usdPer1MTokens(0.02, 0.0)

    @BeforeEach
    fun setUp() {
        listener = EventSavingAgenticEventListener()
        val platform = dummyPlatformServices(eventListener = listener)
        process = SimpleAgentProcess(
            id = "test-process",
            parentId = null,
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platform,
            plannerFactory = DefaultPlannerFactory,
        )
        platform.agentProcessRepository.save(process)
        capturedEvents = mutableListOf()
        embeddingListener = EmbeddingEventListener { capturedEvents += it }
    }

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
    }

    /** Provider model that returns a known Usage in metadata. */
    private class StubModelWithUsage(
        private val tokens: Int,
        private val dims: Int = 4,
    ) : EmbeddingModel {
        override fun dimensions(): Int = dims
        override fun embed(document: Document): FloatArray = FloatArray(dims)
        override fun embed(texts: List<String>): MutableList<FloatArray> =
            texts.map { FloatArray(dims) }.toMutableList()

        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val output = LinkedList<Embedding>()
            for (i in request.instructions.indices) output.add(Embedding(FloatArray(dims), i))
            val md = EmbeddingResponseMetadata("stub", DefaultUsage(tokens, 0))
            return EmbeddingResponse(output, md)
        }
    }

    /** Provider model that does not surface a Usage (e.g. local Ollama). */
    private class StubModelNoUsage(private val dims: Int = 4) : EmbeddingModel {
        override fun dimensions(): Int = dims
        override fun embed(document: Document): FloatArray = FloatArray(dims)
        override fun embed(texts: List<String>): MutableList<FloatArray> =
            texts.map { FloatArray(dims) }.toMutableList()

        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val output = LinkedList<Embedding>()
            for (i in request.instructions.indices) output.add(Embedding(FloatArray(dims), i))
            return EmbeddingResponse(output)
        }
    }

    private fun springAiServiceWith(model: EmbeddingModel) = SpringAiEmbeddingService(
        name = "stub-embedding",
        provider = "Stub",
        model = model,
        configuredDimensions = 4,
        pricingModel = pricing,
    )

    @Test
    fun `embed records invocation against the current AgentProcess`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 1_000_000))
        val ops = EmbeddingOperations(service)

        process.withCurrent {
            ops.embed("hello world")
        }

        assertEquals(1, process.embeddingInvocations.size)
        val recorded = process.embeddingInvocations[0]
        assertEquals(1_000_000, recorded.usage.promptTokens)
        // 1M tokens × $0.02/1M = $0.02
        assertEquals(0.02, recorded.cost(), 1e-9)
    }

    @Test
    fun `embed without an active AgentProcess does not record but still calls the delegate`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 1_000_000))
        val ops = EmbeddingOperations(service)

        // No withCurrent -> AgentProcess.get() returns null
        val result = ops.embed("hello world")

        // Returned vector still has the configured dimensions (4 in the stub)
        assertEquals(4, result.size)
        assertEquals(0, process.embeddingInvocations.size)
        // No AgentProcess wrappers should reach the AgenticEventListener.
        assertTrue(listener.processEvents.none { it is AgentProcessEmbeddingEvent })
    }

    @Test
    fun `embed extracts tokens from EmbeddingResponse metadata when available`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 42))
        val ops = EmbeddingOperations(service)

        process.withCurrent { ops.embed(listOf("a", "b", "c")) }

        assertEquals(42, process.embeddingInvocations.single().usage.promptTokens)
    }

    @Test
    fun `embed falls back to TokenCountEstimator when metadata usage is null`() {
        val service = springAiServiceWith(StubModelNoUsage())
        val ops = EmbeddingOperations(service, tokenCountEstimator = TokenCountEstimator { 7 })

        process.withCurrent { ops.embed("anything") }

        assertEquals(7, process.embeddingInvocations.single().usage.promptTokens)
    }

    @Test
    fun `embed always emits request, model-call and response events on the EmbeddingEventListener regardless of agent presence`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 100))
        val ops = EmbeddingOperations(service, listener = embeddingListener)

        // Without AgentProcess
        ops.embed("hello")
        assertEquals(3, capturedEvents.size, "Expected request, model-call and response events without agent")
        assertTrue(capturedEvents[0] is EmbeddingRequestEvent)
        assertTrue(capturedEvents[1] is EmbeddingModelCallEvent)
        assertTrue(capturedEvents[2] is EmbeddingResponseEvent)

        // The same correlation id flows through all three events.
        val id = (capturedEvents[0] as EmbeddingRequestEvent).id
        assertEquals(id, (capturedEvents[1] as EmbeddingModelCallEvent).id)
        assertEquals(id, (capturedEvents[2] as EmbeddingResponseEvent).id)

        capturedEvents.clear()

        // With AgentProcess
        process.withCurrent { ops.embed("world") }
        assertEquals(3, capturedEvents.size, "Expected request, model-call and response events with agent")
        assertTrue(capturedEvents[0] is EmbeddingRequestEvent)
        assertTrue(capturedEvents[1] is EmbeddingModelCallEvent)
        assertTrue(capturedEvents[2] is EmbeddingResponseEvent)
    }

    @Test
    fun `embed wraps every EmbeddingEvent in AgentProcessEmbeddingEvent on the AgenticEventListener when an agent is active`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 100))
        val ops = EmbeddingOperations(service, listener = embeddingListener)

        process.withCurrent { ops.embed("hello") }

        val wrappers = listener.processEvents.filterIsInstance<AgentProcessEmbeddingEvent>()
        assertEquals(3, wrappers.size, "Expected 3 wrapped events on the AgenticEventListener")
        assertTrue(wrappers[0].embeddingEvent is EmbeddingRequestEvent)
        assertTrue(wrappers[1].embeddingEvent is EmbeddingModelCallEvent)
        assertTrue(wrappers[2].embeddingEvent is EmbeddingResponseEvent)

        // Each wrapper must carry the same EmbeddingEvent instance the standalone listener saw.
        assertSame(capturedEvents[0], wrappers[0].embeddingEvent)
        assertSame(capturedEvents[1], wrappers[1].embeddingEvent)
        assertSame(capturedEvents[2], wrappers[2].embeddingEvent)
    }

    @Test
    fun `embed delegates to the underlying service`() {
        val captured = mutableListOf<List<String>>()
        val custom = object : EmbeddingService {
            override val name = "custom"
            override val provider = "Tester"
            override val pricingModel: PricingModel? = null
            override val dimensions: Int = 3
            override fun embed(text: String): FloatArray =
                embed(listOf(text)).first()

            override fun embed(texts: List<String>): List<FloatArray> {
                captured += texts
                return texts.map { FloatArray(dimensions) }
            }
        }
        val ops = EmbeddingOperations(custom)

        process.withCurrent { ops.embed(listOf("x", "y")) }

        assertEquals(listOf(listOf("x", "y")), captured)
        assertEquals(1, process.embeddingInvocations.size)
    }

    @Test
    fun `embed without an active AgentProcess delivers events to the listener and returns vectors`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 500_000))
        val ops = EmbeddingOperations(service, listener = embeddingListener)

        val result = ops.embed("hello world")

        assertEquals(4, result.size)
        // Listener saw the events even without an agent.
        assertEquals(3, capturedEvents.size)
        // No wrappers on the AgenticEventListener since there is no agent process.
        assertTrue(listener.processEvents.none { it is AgentProcessEmbeddingEvent })
        // No invocation recorded against the agent (none was active).
        assertEquals(0, process.embeddingInvocations.size)
    }

    @Test
    fun `embed surfaces token usage on the response event even without an agent`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 250))
        val ops = EmbeddingOperations(service, listener = embeddingListener)

        ops.embed("hello")

        val response = capturedEvents.filterIsInstance<EmbeddingResponseEvent>().single()
        assertNotNull(response.usage.promptTokens)
        assertEquals(250, response.usage.promptTokens)
    }

    @Test
    fun `embed with non SpringAi delegate emits only request and response events (no model call)`() {
        // EmbeddingModelCallEvent is Spring-AI specific — it's emitted at the
        // EmbeddingModel.call(EmbeddingRequest) seam. Custom delegates that don't go
        // through Spring AI bypass that seam and thus must not emit a model-call event.
        val custom = object : EmbeddingService {
            override val name = "custom"
            override val provider = "Tester"
            override val pricingModel: PricingModel? = null
            override val dimensions: Int = 3
            override fun embed(text: String): FloatArray = embed(listOf(text)).first()
            override fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(dimensions) }
        }
        val ops = EmbeddingOperations(custom, listener = embeddingListener)

        ops.embed("hello")

        assertEquals(2, capturedEvents.size, "Expected request + response events, no model-call")
        assertTrue(capturedEvents[0] is EmbeddingRequestEvent)
        assertTrue(capturedEvents[1] is EmbeddingResponseEvent)
        assertTrue(capturedEvents.none { it is EmbeddingModelCallEvent })
    }

    @Test
    fun `embed with throwing delegate emits request event but not response, and propagates the exception`() {
        val erroringDelegate = object : EmbeddingService {
            override val name = "broken"
            override val provider = "broken"
            override val pricingModel: PricingModel? = null
            override val dimensions: Int = 0
            override fun embed(text: String): FloatArray = throw IllegalStateException("provider blew up")
            override fun embed(texts: List<String>): List<FloatArray> = throw IllegalStateException("provider blew up")
        }
        val ops = EmbeddingOperations(erroringDelegate, listener = embeddingListener)

        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            ops.embed("boom")
        }
        assertEquals("provider blew up", ex.message)

        // Request event was emitted before the failure so observability tools can correlate it.
        assertEquals(1, capturedEvents.size, "Only the request event should have been emitted")
        assertTrue(capturedEvents.single() is EmbeddingRequestEvent)
    }

    @Test
    fun `embed with throwing delegate under active agent does not record invocation nor dispatch response wrapper`() {
        val erroringDelegate = object : EmbeddingService {
            override val name = "broken"
            override val provider = "broken"
            override val pricingModel: PricingModel? = null
            override val dimensions: Int = 0
            override fun embed(text: String): FloatArray = throw IllegalStateException("provider blew up")
            override fun embed(texts: List<String>): List<FloatArray> = throw IllegalStateException("provider blew up")
        }
        val ops = EmbeddingOperations(erroringDelegate, listener = embeddingListener)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            process.withCurrent { ops.embed("boom") }
        }

        // Standalone listener: only the request event should have been emitted.
        assertEquals(1, capturedEvents.size)
        assertTrue(capturedEvents.single() is EmbeddingRequestEvent)

        // Process must NOT have recorded a failed invocation (cost reporting would lie otherwise).
        assertEquals(0, process.embeddingInvocations.size, "Failed call must not be recorded as an invocation")

        // AgenticEventListener: only the request wrapper, never a response wrapper.
        val wrappers = listener.processEvents.filterIsInstance<AgentProcessEmbeddingEvent>()
        assertEquals(1, wrappers.size, "Only the request wrapper should reach the AgenticEventListener")
        assertTrue(wrappers.single().embeddingEvent is EmbeddingRequestEvent)
    }

    @Test
    fun `embed preserves the input texts on request and response events`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 100))
        val ops = EmbeddingOperations(service, listener = embeddingListener)

        val inputs = listOf("alpha", "beta", "gamma")
        ops.embed(inputs)

        val request = capturedEvents.filterIsInstance<EmbeddingRequestEvent>().single()
        val response = capturedEvents.filterIsInstance<EmbeddingResponseEvent>().single()
        assertEquals(inputs, request.inputs)
        assertEquals(inputs, response.inputs)
    }

    @Test
    fun `embed across multiple sequential calls accumulates 3 events per call on the listener`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 10))
        val ops = EmbeddingOperations(service, listener = embeddingListener)

        ops.embed("a")
        ops.embed("b")
        ops.embed(listOf("c", "d"))

        assertEquals(9, capturedEvents.size, "Expected 3 events per call × 3 calls")

        val ids = capturedEvents.map {
            when (it) {
                is EmbeddingRequestEvent -> it.id
                is EmbeddingModelCallEvent -> it.id
                is EmbeddingResponseEvent -> it.id
                else -> error("Unexpected event type: ${it::class}")
            }
        }
        // Each batch of 3 events shares one id; 3 distinct ids overall.
        assertEquals(3, ids.distinct().size, "Each call should produce a unique correlation id")
    }

    @Test
    fun `a throwing custom listener does not break the in-process dispatch`() {
        val service = springAiServiceWith(StubModelWithUsage(tokens = 100))
        val throwingListener = EmbeddingEventListener { error("listener boom") }
        val ops = EmbeddingOperations(service, listener = throwingListener)

        // Must not propagate — listener failures must not bring down the embedding call,
        // and must not prevent the in-process AgentProcessEmbeddingEvent dispatch.
        process.withCurrent { ops.embed("hello") }

        // The wrappers still reached the AgenticEventListener despite the listener throwing.
        val wrappers = listener.processEvents.filterIsInstance<AgentProcessEmbeddingEvent>()
        assertEquals(3, wrappers.size, "Wrapper events must fire even when the listener throws")
        // And the invocation was still recorded.
        assertEquals(1, process.embeddingInvocations.size)
    }
}
