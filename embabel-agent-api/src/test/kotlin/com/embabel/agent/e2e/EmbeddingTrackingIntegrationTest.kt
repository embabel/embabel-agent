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
package com.embabel.agent.e2e

import com.embabel.agent.AgentApiTestApplication
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.support.AgentProcessAccessor
import com.embabel.agent.spi.support.embedding.EmbeddingOperations
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.embedding.EmbeddingResponseMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end test for embedding tracking & pricing.
 *
 * Boots the full Spring context with a [TrackingFakeEmbeddingModel] wrapped in a
 * `@Primary` [SpringAiEmbeddingService], so the [EmbeddingTrackingConfiguration]
 * BeanPostProcessor is exercised for real.
 *
 * Asserts on observable side-effects (recorded invocations, cost, dimensions) across
 * agent / no-agent / cross-thread scenarios. Event ordering is covered by
 * `EmbeddingOperationsTest`.
 */
@SpringBootTest(
    classes = [AgentApiTestApplication::class],
    properties = [
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@ActiveProfiles("test")
@Import(EmbeddingTrackingITConfig::class)
class EmbeddingTrackingIntegrationTest(
    @param:Autowired private val applicationContext: ApplicationContext,
    @param:Autowired private val agentPlatform: AgentPlatform,
    @param:Autowired private val embeddingService: EmbeddingService,
    @param:Autowired private val fakeModel: TrackingFakeEmbeddingModel,
) {

    private val pricing = PricingModel.usdPer1MTokens(0.02, 0.0)

    @BeforeEach
    fun beforeEach() {
        fakeModel.reset()
    }

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
    }

    @Nested
    inner class WrappingByBeanPostProcessor {

        @Test
        fun `every EmbeddingService bean is wrapped in EmbeddingOperations`() {
            val beans = applicationContext.getBeansOfType(EmbeddingService::class.java)
            assertTrue(beans.isNotEmpty(), "Test config registers at least one EmbeddingService")
            beans.forEach { (name, bean) ->
                assertTrue(
                    bean is EmbeddingOperations,
                    "Bean '$name' (${bean::class.qualifiedName}) should be wrapped in EmbeddingOperations",
                )
            }
        }

        @Test
        fun `direct injection of EmbeddingService gets the wrapped instance`() {
            // The constructor-injected `embeddingService` is what RAG and other consumers see.
            assertTrue(embeddingService is EmbeddingOperations)
        }

        @Test
        fun `wrapped service still exposes the underlying provider's metadata`() {
            assertEquals("integration-test-embedding", embeddingService.name)
            assertEquals("integration-test", embeddingService.provider)
            assertNotNull(embeddingService.pricingModel)
            assertEquals(0.02, embeddingService.pricingModel!!.usdPerInputToken() * 1_000_000, 1e-9)
            assertEquals(8, embeddingService.dimensions)
        }
    }

    @Nested
    inner class TrackingThroughAgentProcess {

        @Test
        fun `embed within an active AgentProcess records invocation and computes cost`() {
            val agentProcess = newAgentProcess()
            fakeModel.nextTokens.set(750_000)

            agentProcess.withCurrent {
                embeddingService.embed(listOf("alpha", "beta", "gamma"))
            }

            assertEquals(1, agentProcess.embeddingInvocations.size, "One invocation should be recorded")
            val invocation = agentProcess.embeddingInvocations.single()
            assertEquals("integration-test-embedding", invocation.embeddingMetadata.name)
            assertEquals(750_000, invocation.usage.promptTokens)

            // 750 000 tokens × $0.02 / 1M = $0.015
            assertEquals(0.015, invocation.cost(), 1e-9)
            assertEquals(0.015, agentProcess.totalCost(), 1e-9)
        }

        @Test
        fun `embed without an active AgentProcess does not record but still returns a vector`() {
            // No withCurrent → no AgentProcess in scope → no tracking.
            fakeModel.nextTokens.set(123)

            val result = embeddingService.embed("orphan")

            assertEquals(8, result.size, "Vector dimension should match the underlying model")
        }

        @Test
        fun `multiple sequential embeds are all recorded`() {
            val agentProcess = newAgentProcess()
            fakeModel.nextTokens.set(100_000)

            agentProcess.withCurrent {
                embeddingService.embed("a")
                embeddingService.embed("b")
                embeddingService.embed(listOf("c", "d"))
            }

            assertEquals(3, agentProcess.embeddingInvocations.size)
            // 3 calls × 100 000 tokens × $0.02 / 1M = $0.006
            assertEquals(0.006, agentProcess.totalCost(), 1e-9)
        }
    }

    @Nested
    inner class TokenSourcing {

        @Test
        fun `provider tokens are used when metadata is present`() {
            val agentProcess = newAgentProcess()
            fakeModel.nextTokens.set(1234)

            agentProcess.withCurrent {
                embeddingService.embed("a 32-character text used for testing")
            }

            assertEquals(1234, agentProcess.embeddingInvocations.single().usage.promptTokens)
        }

        @Test
        fun `falls back to estimator when provider returns no usage`() {
            val agentProcess = newAgentProcess()
            // 0 → Spring AI returns EmptyUsage with promptTokens=0; we treat that as "no data".
            fakeModel.nextTokens.set(0)

            agentProcess.withCurrent {
                // 35 chars → heuristic ≈ 9 tokens (4 chars/token, ceil)
                embeddingService.embed("a 32-character text used for testin")
            }

            val tokens = agentProcess.embeddingInvocations.single().usage.promptTokens
            assertNotNull(tokens)
            assertTrue(tokens!! > 0, "Estimator should produce >0 tokens, got $tokens")
        }
    }

    @Nested
    inner class CrossThreadPropagation {

        @Test
        fun `tracking works on a worker thread when AgentProcess is propagated by ExecutorAsyncer`() {
            val executor = Executors.newSingleThreadExecutor()
            val asyncer = ExecutorAsyncer(executor)
            try {
                val agentProcess = newAgentProcess()
                fakeModel.nextTokens.set(500_000)

                AgentProcessAccessor.setValue(agentProcess)
                val future = asyncer.async {
                    // Worker thread should see the captured AgentProcess.
                    embeddingService.embed("from-worker")
                }
                future.get(5, TimeUnit.SECONDS)

                assertEquals(
                    1, agentProcess.embeddingInvocations.size,
                    "Worker-thread embedding should record on the original AgentProcess",
                )
                assertEquals(500_000, agentProcess.embeddingInvocations.single().usage.promptTokens)
                // 500 000 × $0.02/1M = $0.01
                assertEquals(0.01, agentProcess.totalCost(), 1e-9)
            } finally {
                executor.shutdown()
                executor.awaitTermination(2, TimeUnit.SECONDS)
            }
        }
    }

    private fun newAgentProcess(): AgentProcess {
        val agent = com.embabel.agent.api.dsl.evenMoreEvilWizard()
        agentPlatform.deploy(agent)
        return agentPlatform.createAgentProcess(agent, ProcessOptions(), emptyMap())
    }
}

/** Provider-side fake [EmbeddingModel] that lets the test control the tokens to surface. */
class TrackingFakeEmbeddingModel(private val dims: Int = 8) : EmbeddingModel {

    /** Tokens reported via `EmbeddingResponse.metadata.usage` for the next call. */
    val nextTokens = AtomicInteger(0)

    override fun dimensions(): Int = dims

    override fun embed(document: Document): FloatArray = FloatArray(dims)

    override fun embed(texts: List<String>): MutableList<FloatArray> =
        texts.map { FloatArray(dims) }.toMutableList()

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val output = LinkedList<Embedding>()
        for (i in request.instructions.indices) output.add(Embedding(FloatArray(dims), i))
        val md = EmbeddingResponseMetadata("integration-test", DefaultUsage(nextTokens.get(), 0))
        return EmbeddingResponse(output, md)
    }

    fun reset() {
        nextTokens.set(0)
    }
}

/**
 * Test-only configuration that replaces any autoconfigure-supplied `EmbeddingService` with a
 * [TrackingFakeEmbeddingModel] wrapped in a [SpringAiEmbeddingService]. The production
 * BeanPostProcessor then automatically wraps that bean in [EmbeddingOperations].
 */
@TestConfiguration
open class EmbeddingTrackingITConfig {

    @Bean
    open fun fakeEmbeddingModel(): TrackingFakeEmbeddingModel = TrackingFakeEmbeddingModel()

    @Bean
    @Primary
    open fun fakeEmbeddingService(fakeModel: TrackingFakeEmbeddingModel): EmbeddingService =
        SpringAiEmbeddingService(
            name = "integration-test-embedding",
            provider = "integration-test",
            model = fakeModel,
            configuredDimensions = 8,
            pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
        )
}
