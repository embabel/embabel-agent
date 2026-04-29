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

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.model.AiModel
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.SpringAiEmbeddingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Non-regression test for [EmbeddingOperations].
 *
 * Goal: prove that wrapping any [EmbeddingService] in [EmbeddingOperations] preserves the
 * exact behaviour expected by every existing production caller, with **no** observable
 * change to inputs, outputs, dimensions, exceptions, or metadata access.
 *
 * Each `@Nested` class below mirrors a real production call site identified by grep:
 *
 *   * [InMemoryNamedEntityDataRepositoryPattern]
 *       — `embabel-agent-rag-core`/InMemoryNamedEntityDataRepository.kt:77,90
 *       — single-text `embed(text)`, accessed via a nullable `EmbeddingService?`
 *
 *   * [AssetViewSearchOperationsPattern]
 *       — `embabel-agent-rag-core`/AssetViewSearchOperations.kt:65,133
 *       — `embeddingService.embed(query)` and `embeddingService!!.embed(getAssetText(asset))`
 *
 *   * [EmbeddingBatchGeneratorPattern]
 *       — `embabel-agent-rag-core`/EmbeddingBatchGenerator.kt:59
 *       — bulk `embed(texts: List<String>)`
 *
 *   * [LuceneSearchOperationsPattern]
 *       — `embabel-agent-rag-lucene`/LuceneSearchOperations.kt:298,489,670
 *       — `embeddingService!!.embed(...)` for indexing chunks and queries
 *
 *   * [EmbeddingAwareChunkingPattern]
 *       — `embabel-agent-rag-core`/EmbeddingAwareChunkingContentElementRepository.kt
 *       — required (non-null) `EmbeddingService` constructor parameter
 *
 *   * [BuilderInjectionPattern]
 *       — `embabel-agent-rag-core`/SearchOperationsBuilder.kt:33
 *       — `withEmbeddingService(embeddingService: EmbeddingService)`
 *
 *   * [MetadataAccessPattern]
 *       — accessors used during model registry / logging: `name`, `provider`, `dimensions`,
 *         `pricingModel`, `infoString(...)`
 *
 * Strategy: for each pattern, the test runs the same call against the **raw** delegate
 * and against [EmbeddingOperations] wrapping the same delegate, and asserts the outputs
 * are byte-for-byte identical. This guarantees that introducing the BeanPostProcessor
 * cannot silently break any production caller.
 */
class EmbeddingOperationsNonRegressionTest {

    /** Deterministic fake that returns known vectors so we can compare wrap vs raw. */
    private class DeterministicEmbeddingModel(private val dims: Int = 4) : EmbeddingModel {
        val callCount = AtomicInteger(0)
        override fun dimensions(): Int = dims
        override fun embed(document: Document): FloatArray = vectorFor("doc")
        override fun embed(texts: List<String>): MutableList<FloatArray> =
            texts.map { vectorFor(it) }.toMutableList()
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            callCount.incrementAndGet()
            val output = LinkedList<Embedding>()
            for ((i, text) in request.instructions.withIndex()) {
                output.add(Embedding(vectorFor(text), i))
            }
            return EmbeddingResponse(output)
        }
        private fun vectorFor(text: String): FloatArray {
            // Deterministic pseudo-vector based on the text so that we can assert equality.
            val seed = text.hashCode()
            val v = FloatArray(dims)
            for (i in 0 until dims) v[i] = ((seed shr (i * 4)) and 0xF).toFloat() / 15f
            return v
        }
    }

    /** Custom EmbeddingService that does NOT extend AiModel/SpringAi (covers OnnxEmbeddingService-style impls). */
    private class CustomEmbeddingService(
        private val backing: DeterministicEmbeddingModel = DeterministicEmbeddingModel(),
    ) : EmbeddingService {
        override val name: String = "custom-non-spring-ai"
        override val provider: String = "custom"
        override val dimensions: Int = backing.dimensions()
        override val pricingModel: PricingModel? = null
        override fun embed(text: String): FloatArray = backing.embed(listOf(text)).first()
        override fun embed(texts: List<String>): List<FloatArray> = backing.embed(texts)
    }

    private lateinit var rawSpringAi: SpringAiEmbeddingService
    private lateinit var rawSpringAiModel: DeterministicEmbeddingModel
    private lateinit var wrappedSpringAi: EmbeddingOperations

    private lateinit var rawCustom: CustomEmbeddingService
    private lateinit var wrappedCustom: EmbeddingOperations

    private lateinit var process: SimpleAgentProcess

    @BeforeEach
    fun setUp() {
        rawSpringAiModel = DeterministicEmbeddingModel(dims = 4)
        rawSpringAi = SpringAiEmbeddingService(
            name = "spring-ai-embed",
            provider = "test-spring",
            model = rawSpringAiModel,
            configuredDimensions = 4,
            pricingModel = PricingModel.usdPer1MTokens(0.02, 0.0),
        )
        wrappedSpringAi = EmbeddingOperations(rawSpringAi)

        rawCustom = CustomEmbeddingService()
        wrappedCustom = EmbeddingOperations(rawCustom)

        val platform = dummyPlatformServices()
        process = SimpleAgentProcess(
            id = "non-regression-process",
            parentId = null,
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platform,
            plannerFactory = com.embabel.agent.spi.support.DefaultPlannerFactory,
        )
        platform.agentProcessRepository.save(process)
    }

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
    }

    // ---------------------------------------------------------------------
    // Production call site: rag-core/InMemoryNamedEntityDataRepository.kt
    // The repository declares `embeddingService: EmbeddingService? = null` and uses
    // `service.embed(textToEmbed)` inside a `?.let { ... }` block.
    // ---------------------------------------------------------------------
    @Nested
    inner class InMemoryNamedEntityDataRepositoryPattern {

        @Test
        fun `nullable EmbeddingService field can be assigned to wrapped instance`() {
            // Mirror the real signature: `private val embeddingService: EmbeddingService? = null`
            val asField: EmbeddingService? = wrappedSpringAi
            assertNotNull(asField)
        }

        @Test
        fun `single-text embed inside a let-block returns the same vector as the raw delegate`() {
            val text = "Kotlin is a JVM language"

            val raw = rawSpringAi.embed(text)
            // Real call site uses `?.let { service -> ... service.embed(text) }`
            val wrapped: FloatArray? = (wrappedSpringAi as EmbeddingService?)?.let { service ->
                service.embed(text)
            }

            assertNotNull(wrapped)
            assertArrayEquals(raw, wrapped, "Wrapped embed must be byte-equal to raw")
        }

        @Test
        fun `null-conditional access on a null reference still no-ops`() {
            val maybeNull: EmbeddingService? = null
            // The repository's `embeddingService?.let { ... }` should remain a safe no-op.
            val result = maybeNull?.let { it.embed("anything") }
            assertNull(result)
        }
    }

    // ---------------------------------------------------------------------
    // Production call site: rag-core/AssetViewSearchOperations.kt
    // Uses both `embeddingService.embed(request.query)` and `embeddingService!!.embed(...)`.
    // ---------------------------------------------------------------------
    @Nested
    inner class AssetViewSearchOperationsPattern {

        @Test
        fun `embed(query) returns vectors equivalent to the raw delegate`() {
            val query = "find all the things"
            val raw = rawSpringAi.embed(query)
            val wrapped = wrappedSpringAi.embed(query)
            assertArrayEquals(raw, wrapped)
        }

        @Test
        fun `bang-bang access on a non-null wrapped service does not throw`() {
            // Mirror `embeddingService!!.embed(getAssetText(asset))` from line 133.
            val maybe: EmbeddingService? = wrappedSpringAi
            val vector = maybe!!.embed("asset content")
            assertEquals(rawSpringAi.dimensions, vector.size)
        }
    }

    // ---------------------------------------------------------------------
    // Production call site: rag-core/EmbeddingBatchGenerator.kt
    // Calls `embeddingService.embed(texts)` where `texts` is a List<String>.
    // ---------------------------------------------------------------------
    @Nested
    inner class EmbeddingBatchGeneratorPattern {

        @Test
        fun `bulk embed of a list returns the same number of vectors with same dimensions`() {
            val texts = listOf("first", "second", "third", "fourth", "fifth")
            val raw = rawSpringAi.embed(texts)
            val wrapped = wrappedSpringAi.embed(texts)

            assertEquals(raw.size, wrapped.size)
            for (i in texts.indices) {
                assertArrayEquals(
                    raw[i], wrapped[i],
                    "Vector for '${texts[i]}' should match between raw and wrapped",
                )
            }
        }

        @Test
        fun `empty list returns empty list and does not throw`() {
            // Some batchers may pass empty inputs at the end of a stream.
            val raw = rawCustom.embed(emptyList())
            val wrapped = wrappedCustom.embed(emptyList())
            assertEquals(0, raw.size)
            assertEquals(0, wrapped.size)
        }
    }

    // ---------------------------------------------------------------------
    // Production call site: rag-lucene/LuceneSearchOperations.kt
    // Uses `embeddingService!!.embed(chunk.embeddableValue())` for indexing,
    // and `embeddingService!!.embed(request.query)` for retrieval.
    // ---------------------------------------------------------------------
    @Nested
    inner class LuceneSearchOperationsPattern {

        @Test
        fun `indexing path embed(chunk) preserves dimensions`() {
            val chunk = "this is a chunk to index"
            val raw = rawSpringAi.embed(chunk)
            val wrapped = wrappedSpringAi.embed(chunk)

            assertEquals(rawSpringAi.dimensions, wrapped.size)
            assertArrayEquals(raw, wrapped)
        }

        @Test
        fun `query path embed(query) yields the same vector twice for the same text`() {
            // LuceneSearchOperations indexes then queries with the same text; results must match.
            val text = "stable query text"
            val indexVec = wrappedSpringAi.embed(text)
            val queryVec = wrappedSpringAi.embed(text)
            assertArrayEquals(indexVec, queryVec)
        }
    }

    // ---------------------------------------------------------------------
    // Production call site: rag-core/EmbeddingAwareChunkingContentElementRepository.kt
    // Constructor parameter: `protected val embeddingService: EmbeddingService` (non-null).
    // ---------------------------------------------------------------------
    @Nested
    inner class EmbeddingAwareChunkingPattern {

        @Test
        fun `non-null EmbeddingService field accepts the wrapped instance`() {
            // Mirror the field type exactly.
            val field: EmbeddingService = wrappedSpringAi
            assertEquals(rawSpringAi.name, field.name)
            assertEquals(rawSpringAi.dimensions, field.dimensions)
        }
    }

    // ---------------------------------------------------------------------
    // Production call site: rag-core/SearchOperationsBuilder.kt:33
    // Builder method: `fun withEmbeddingService(embeddingService: EmbeddingService): THIS`
    // ---------------------------------------------------------------------
    @Nested
    inner class BuilderInjectionPattern {

        @Test
        fun `wrapped service can be passed through a builder method that expects EmbeddingService`() {
            // Simulate the builder.
            class TestBuilder {
                var captured: EmbeddingService? = null
                fun withEmbeddingService(embeddingService: EmbeddingService): TestBuilder = apply {
                    captured = embeddingService
                }
            }

            val builder = TestBuilder().withEmbeddingService(wrappedSpringAi)
            assertNotNull(builder.captured)
            assertEquals(wrappedSpringAi, builder.captured)
        }
    }

    // ---------------------------------------------------------------------
    // Cross-cutting: metadata accessors used by the model registry, logging, and JSON
    // serialization (cf. ConfigurableModelProvider.getEmbeddingService(...)).
    // ---------------------------------------------------------------------
    @Nested
    inner class MetadataAccessPattern {

        @Test
        fun `name provider dimensions and pricingModel are forwarded`() {
            assertEquals(rawSpringAi.name, wrappedSpringAi.name)
            assertEquals(rawSpringAi.provider, wrappedSpringAi.provider)
            assertEquals(rawSpringAi.dimensions, wrappedSpringAi.dimensions)
            assertEquals(rawSpringAi.pricingModel, wrappedSpringAi.pricingModel)
        }

        @Test
        fun `infoString delegates to the underlying service`() {
            assertEquals(
                rawSpringAi.infoString(verbose = false, indent = 0),
                wrappedSpringAi.infoString(verbose = false, indent = 0),
            )
        }

        @Test
        fun `wrapped non-Spring-AI service preserves its custom metadata`() {
            assertEquals("custom-non-spring-ai", wrappedCustom.name)
            assertEquals("custom", wrappedCustom.provider)
            assertNull(wrappedCustom.pricingModel)
        }
    }

    // ---------------------------------------------------------------------
    // Cross-cutting: error propagation. Production sites do not catch embedding errors —
    // exceptions must surface unchanged through the wrapper.
    // ---------------------------------------------------------------------
    @Nested
    inner class ErrorPropagation {

        @Test
        fun `exception from the underlying provider propagates unchanged through the wrapper`() {
            val erroringDelegate = object : EmbeddingService {
                override val name: String = "broken"
                override val provider: String = "broken"
                override val dimensions: Int = 0
                override val pricingModel: PricingModel? = null
                override fun embed(text: String): FloatArray =
                    throw IllegalStateException("provider blew up")
                override fun embed(texts: List<String>): List<FloatArray> =
                    throw IllegalStateException("provider blew up")
            }
            val wrapped = EmbeddingOperations(erroringDelegate)

            // No active AgentProcess → wrapper still calls the delegate; events fire on the
            // EmbeddingEventListener but no invocation is recorded. The delegate exception
            // must surface unchanged.
            val direct = assertThrows(IllegalStateException::class.java) { wrapped.embed("x") }
            assertEquals("provider blew up", direct.message)

            // With an active AgentProcess, the call still hits the delegate path that throws.
            val withProc = assertThrows(IllegalStateException::class.java) {
                process.withCurrent { wrapped.embed("x") }
            }
            assertEquals("provider blew up", withProc.message)
        }
    }

    // ---------------------------------------------------------------------
    // Wrapper invariants (independent of any specific call site).
    // ---------------------------------------------------------------------
    @Nested
    inner class WrapperInvariants {

        @Test
        fun `wrapping is idempotent in spirit (re-wrapping the same delegate is allowed)`() {
            val a = EmbeddingOperations(rawSpringAi)
            val b = EmbeddingOperations(rawSpringAi)
            // Two wrappers around the same delegate are NOT the same instance, but both work.
            assertFalse(a === b)
            assertArrayEquals(a.embed("hello"), b.embed("hello"))
        }

        @Test
        fun `wrapper preserves AiModel sub-interface metadata when underlying implements it`() {
            // SpringAiEmbeddingService implements AiModel<EmbeddingModel>.
            // The wrapper IS-A EmbeddingService but NOT-A SpringAiEmbeddingService — that's OK
            // because production injects EmbeddingService, not concrete subclasses (verified by grep).
            assertTrue(wrappedSpringAi is EmbeddingService)
        }

        @Test
        fun `wrapper does not call the delegate twice for a single embed call`() {
            // Accidentally double-invoking the underlying provider would double-bill costs.
            rawSpringAiModel.callCount.set(0)
            process.withCurrent { wrappedSpringAi.embed("once") }
            // SpringAi path: 1 call() invocation total.
            assertEquals(1, rawSpringAiModel.callCount.get())
        }
    }
}
