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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.EmbeddingInvocation
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EmbeddingTrackingInAgentProcessTest {

    private lateinit var platformServices: PlatformServices
    private lateinit var process: SimpleAgentProcess

    private val embeddingMetadata = EmbeddingServiceMetadata(
        name = "text-embedding-3-small",
        provider = "OpenAI",
        pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
    )

    @BeforeEach
    fun setUp() {
        platformServices = dummyPlatformServices()
        process = SimpleAgentProcess(
            id = "p1",
            parentId = null,
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        platformServices.agentProcessRepository.save(process)
    }

    private fun anEmbeddingInvocation(tokens: Int) = EmbeddingInvocation(
        embeddingMetadata = embeddingMetadata,
        usage = Usage(promptTokens = tokens, completionTokens = 0, nativeUsage = null),
        agentName = SimpleTestAgent.name,
        timestamp = Instant.now(),
        runningTime = Duration.ofMillis(10),
    )

    @Test
    fun `recordEmbeddingInvocation adds to embeddingInvocations list`() {
        val invocation = anEmbeddingInvocation(500_000)
        process.recordEmbeddingInvocation(invocation)
        assertEquals(1, process.embeddingInvocations.size)
        assertEquals(invocation, process.embeddingInvocations[0])
    }

    @Test
    fun `totalCost includes embedding invocation cost while cost stays LLM-only`() {
        process.recordEmbeddingInvocation(anEmbeddingInvocation(1_000_000))
        // 1M tokens × $0.02 / 1M = $0.02
        assertEquals(0.02, process.totalCost(), 1e-9)
        assertEquals(0.02, process.embeddingCost(), 1e-9)
        assertEquals(0.0, process.cost(), 1e-9, "cost() is LLM-only")
    }

    @Test
    fun `totalCost sums LLM and embedding invocations`() {
        val mockLlm = LlmMetadata.create(
            "gpt-4",
            "OpenAI",
            null,
            PricingModel.usdPer1MTokens(10.0, 30.0),
        )
        process.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = mockLlm,
                usage = Usage(1_000_000, 0, null),
                timestamp = Instant.now(),
                runningTime = Duration.ZERO,
            )
        )
        process.recordEmbeddingInvocation(anEmbeddingInvocation(1_000_000))
        // LLM: 1M × $10/1M = $10. Embedding: 1M × $0.02/1M = $0.02.
        assertEquals(10.02, process.totalCost(), 1e-9)
        assertEquals(10.0, process.cost(), 1e-9)
        assertEquals(0.02, process.embeddingCost(), 1e-9)
    }

    @Test
    fun `parent totalCost includes child's embedding cost via subtree aggregation`() {
        val child = SimpleAgentProcess(
            id = "child",
            parentId = "p1",
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        platformServices.agentProcessRepository.save(child)
        child.recordEmbeddingInvocation(anEmbeddingInvocation(2_000_000))
        // Child: 2M × $0.02/1M = $0.04. Parent inherits via subtree recursion.
        assertEquals(0.04, child.totalCost(), 1e-9)
        assertEquals(0.04, process.totalCost(), 1e-9)
    }

    @Test
    fun `recordEmbeddingInvocation is thread-safe under concurrent calls`() {
        // Guards the CopyOnWriteArrayList choice in AbstractAgentProcess._embeddingInvocations.
        // A previous incident (#1602) shipped a non-thread-safe MutableList for the LLM
        // counterpart; this test pins the contract for embeddings so the same regression
        // can't happen silently.
        val threadCount = 16
        val invocationsPerThread = 250
        val expectedTotal = threadCount * invocationsPerThread

        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            pool.submit {
                ready.countDown()
                start.await()
                repeat(invocationsPerThread) {
                    process.recordEmbeddingInvocation(anEmbeddingInvocation(1))
                }
                done.countDown()
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        assertEquals(true, done.await(10, TimeUnit.SECONDS), "Threads did not finish in time")
        pool.shutdown()

        assertEquals(expectedTotal, process.embeddingInvocations.size)
    }

    @Test
    fun `embedding invocation with no pricing contributes zero to totalCost`() {
        val freeMetadata = EmbeddingServiceMetadata(
            name = "all-MiniLM-L6-v2",
            provider = "ONNX",
        )
        process.recordEmbeddingInvocation(
            EmbeddingInvocation(
                embeddingMetadata = freeMetadata,
                usage = Usage(promptTokens = 5_000_000, completionTokens = 0, nativeUsage = null),
                agentName = null,
                timestamp = Instant.now(),
                runningTime = Duration.ZERO,
            )
        )
        assertEquals(0.0, process.totalCost(), 1e-9)
    }

    @Test
    fun `totalUsage combines LLM prompt and completion with embedding prompt tokens`() {
        val mockLlm = LlmMetadata.create("gpt-4", "OpenAI", null, PricingModel.usdPer1MTokens(10.0, 30.0))
        process.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = mockLlm,
                usage = Usage(1_000, 500, null),
                timestamp = Instant.now(),
                runningTime = Duration.ZERO,
            )
        )
        process.recordEmbeddingInvocation(anEmbeddingInvocation(2_000))

        val total = process.totalUsage()
        assertEquals(3_000, total.promptTokens, "prompt = LLM 1_000 + embedding 2_000")
        assertEquals(500, total.completionTokens, "completion is LLM-only")
    }

    @Test
    fun `totalUsage aggregates LLM and embedding across parent and child`() {
        val child = SimpleAgentProcess(
            id = "child-usage",
            parentId = "p1",
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        platformServices.agentProcessRepository.save(child)

        val mockLlm = LlmMetadata.create("gpt-4", "OpenAI", null, PricingModel.usdPer1MTokens(10.0, 30.0))
        process.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = mockLlm,
                usage = Usage(1_000, 200, null),
                timestamp = Instant.now(),
                runningTime = Duration.ZERO,
            )
        )
        process.recordEmbeddingInvocation(anEmbeddingInvocation(500))
        child.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = mockLlm,
                usage = Usage(700, 300, null),
                timestamp = Instant.now(),
                runningTime = Duration.ZERO,
            )
        )
        child.recordEmbeddingInvocation(anEmbeddingInvocation(1_500))

        val total = process.totalUsage()
        assertEquals(3_700, total.promptTokens, "prompt = LLM 1_000+700 + embedding 500+1_500")
        assertEquals(500, total.completionTokens, "completion = LLM 200+300 only")
    }

    @Test
    fun `totalModelsUsed returns LLM and embedding models combined and deduplicated`() {
        val gpt4 = LlmMetadata.create("gpt-4", "OpenAI", null, PricingModel.usdPer1MTokens(10.0, 30.0))
        process.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = gpt4,
                usage = Usage(100, 0, null),
                timestamp = Instant.now(),
                runningTime = Duration.ZERO,
            )
        )
        process.recordEmbeddingInvocation(anEmbeddingInvocation(100))
        process.recordEmbeddingInvocation(anEmbeddingInvocation(200))

        val all = process.totalModelsUsed()
        assertEquals(2, all.size, "LLM + 1 embedding (dedup)")
        assertEquals(setOf("gpt-4", "text-embedding-3-small"), all.map { it.name }.toSet())
    }

    @Test
    fun `totalModelsUsed aggregates models across parent and child subtrees`() {
        val child = SimpleAgentProcess(
            id = "child-models",
            parentId = "p1",
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        platformServices.agentProcessRepository.save(child)

        val gpt4 = LlmMetadata.create("gpt-4", "OpenAI", null, PricingModel.usdPer1MTokens(10.0, 30.0))
        val claude = LlmMetadata.create("claude", "Anthropic", null, PricingModel.usdPer1MTokens(3.0, 15.0))
        process.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = gpt4, usage = Usage(10, 0, null),
                timestamp = Instant.now(), runningTime = Duration.ZERO,
            )
        )
        process.recordEmbeddingInvocation(anEmbeddingInvocation(10))
        child.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = claude, usage = Usage(10, 0, null),
                timestamp = Instant.now(), runningTime = Duration.ZERO,
            )
        )

        val all = process.totalModelsUsed().map { it.name }.toSet()
        assertEquals(setOf("claude", "gpt-4", "text-embedding-3-small"), all)
    }

    @Test
    fun `embeddingCost aggregates across parent and child subtrees`() {
        val child = SimpleAgentProcess(
            id = "child-emb-cost",
            parentId = "p1",
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        platformServices.agentProcessRepository.save(child)

        process.recordEmbeddingInvocation(anEmbeddingInvocation(1_000_000))   // $0.02
        child.recordEmbeddingInvocation(anEmbeddingInvocation(500_000))       // $0.01

        assertEquals(0.01, child.embeddingCost(), 1e-9)
        assertEquals(0.03, process.embeddingCost(), 1e-9, "parent embeddingCost includes child")
    }

    @Test
    fun `embeddingUsage aggregates across parent and child subtrees`() {
        val child = SimpleAgentProcess(
            id = "child-emb-usage",
            parentId = "p1",
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        platformServices.agentProcessRepository.save(child)

        process.recordEmbeddingInvocation(anEmbeddingInvocation(1_000))
        child.recordEmbeddingInvocation(anEmbeddingInvocation(2_500))

        assertEquals(2_500, child.embeddingUsage().promptTokens)
        assertEquals(3_500, process.embeddingUsage().promptTokens, "parent embeddingUsage includes child")
    }

    @Test
    fun `embeddingModelsUsed aggregates across parent and child subtrees`() {
        val child = SimpleAgentProcess(
            id = "child-emb-models",
            parentId = "p1",
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        platformServices.agentProcessRepository.save(child)

        val anotherEmbedding = EmbeddingServiceMetadata(
            name = "text-embedding-3-large",
            provider = "OpenAI",
            pricingModel = PricingModel.usdPer1MTokens(0.13, 0.0),
        )
        process.recordEmbeddingInvocation(anEmbeddingInvocation(100))
        child.recordEmbeddingInvocation(
            EmbeddingInvocation(
                embeddingMetadata = anotherEmbedding,
                usage = Usage(promptTokens = 100, completionTokens = 0, nativeUsage = null),
                agentName = null,
                timestamp = Instant.now(),
                runningTime = Duration.ofMillis(1),
            )
        )

        val names = process.embeddingModelsUsed().map { it.name }
        assertEquals(listOf("text-embedding-3-large", "text-embedding-3-small"), names)
    }

    @Test
    fun `totalCostInfoString combines LLM and embedding sections plus total line`() {
        val gpt4 = LlmMetadata.create("gpt-4", "OpenAI", null, PricingModel.usdPer1MTokens(10.0, 30.0))
        process.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = gpt4,
                usage = Usage(1_000_000, 0, null),
                timestamp = Instant.now(),
                runningTime = Duration.ZERO,
            )
        )
        process.recordEmbeddingInvocation(anEmbeddingInvocation(1_000_000))

        val info = process.totalCostInfoString(verbose = false)
        // LLM line, embedding line, then total line
        assert(info.contains("LLMs: [gpt-4] across 1 calls")) { "Missing LLM line: $info" }
        assert(info.contains("Embeddings: [text-embedding-3-small] across 1 calls")) { "Missing Embedding line: $info" }
        assert(info.contains("Total cost: \$10.0200")) { "Missing total cost: $info" }
    }
}
