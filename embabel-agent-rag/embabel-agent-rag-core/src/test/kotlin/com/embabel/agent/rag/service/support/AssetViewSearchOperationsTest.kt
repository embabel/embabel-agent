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
package com.embabel.agent.rag.service.support

import com.embabel.agent.api.common.LlmReference
import com.embabel.chat.Asset
import com.embabel.chat.AssetTracker
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class AssetViewSearchOperationsTest {

    @Nested
    inner class SupportsTypeTest {

        @Test
        fun `supportsType returns true for Asset`() {
            val tracker = TestAssetTracker()
            val ops = AssetViewSearchOperations(tracker)

            assertTrue(ops.supportsType("Asset"))
        }

        @Test
        fun `supportsType returns true for AssetRetrievable`() {
            val tracker = TestAssetTracker()
            val ops = AssetViewSearchOperations(tracker)

            assertTrue(ops.supportsType("AssetRetrievable"))
        }

        @Test
        fun `supportsType returns false for other types`() {
            val tracker = TestAssetTracker()
            val ops = AssetViewSearchOperations(tracker)

            assertFalse(ops.supportsType("Chunk"))
            assertFalse(ops.supportsType("Document"))
        }
    }

    @Nested
    inner class TextSearchTest {

        private lateinit var tracker: TestAssetTracker
        private lateinit var ops: AssetViewSearchOperations

        @BeforeEach
        fun setup() {
            tracker = TestAssetTracker()
            ops = AssetViewSearchOperations(tracker)

            tracker.addAsset(createAsset("asset-1", "Kotlin Programming", "A modern JVM language"))
            tracker.addAsset(createAsset("asset-2", "Java Programming", "A classic JVM language"))
            tracker.addAsset(createAsset("asset-3", "Python Scripting", "A versatile scripting language"))
        }

        @Test
        fun `textSearch finds matching assets by name`() {
            val request = TextSimilaritySearchRequest(
                query = "Kotlin",
                similarityThreshold = 0.01, // Use positive threshold to exclude zero-score results
                topK = 10
            )

            val results = ops.textSearch(request, AssetRetrievable::class.java)

            assertEquals(1, results.size)
            assertEquals("asset-1", results.first().match.id)
        }

        @Test
        fun `textSearch finds matching assets by description`() {
            val request = TextSimilaritySearchRequest(
                query = "scripting",
                similarityThreshold = 0.01,
                topK = 10
            )

            val results = ops.textSearch(request, AssetRetrievable::class.java)

            assertEquals(1, results.size)
            assertEquals("asset-3", results.first().match.id)
        }

        @Test
        fun `textSearch is case insensitive`() {
            val request = TextSimilaritySearchRequest(
                query = "PROGRAMMING",
                similarityThreshold = 0.01,
                topK = 10
            )

            val results = ops.textSearch(request, AssetRetrievable::class.java)

            assertEquals(2, results.size)
        }

        @Test
        fun `textSearch scores by term match percentage`() {
            val request = TextSimilaritySearchRequest(
                query = "JVM language",
                similarityThreshold = 0.01,
                topK = 10
            )

            val results = ops.textSearch(request, AssetRetrievable::class.java)

            // All three have "language" in description, but only Kotlin and Java have "JVM"
            // Python has 1/2 terms = 0.5, Kotlin and Java have 2/2 = 1.0
            assertEquals(3, results.size)
            // Kotlin and Java should be first with score 1.0
            assertTrue(results.take(2).all { it.score == 1.0 })
            // Python should be third with score 0.5
            assertEquals(0.5, results[2].score, 0.01)
        }

        @Test
        fun `textSearch respects similarity threshold`() {
            val request = TextSimilaritySearchRequest(
                query = "JVM unknown term",
                similarityThreshold = 0.8,
                topK = 10
            )

            val results = ops.textSearch(request, AssetRetrievable::class.java)

            // Score would be 1/3 = 0.33, which is below threshold
            assertTrue(results.isEmpty())
        }

        @Test
        fun `textSearch respects topK limit`() {
            val request = TextSimilaritySearchRequest(
                query = "language",
                similarityThreshold = 0.01,
                topK = 2
            )

            val results = ops.textSearch(request, AssetRetrievable::class.java)

            assertEquals(2, results.size)
        }

        @Test
        fun `textSearch returns empty for no matches`() {
            val request = TextSimilaritySearchRequest(
                query = "rust",
                similarityThreshold = 0.01,
                topK = 10
            )

            val results = ops.textSearch(request, AssetRetrievable::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `textSearch works with empty asset tracker`() {
            val emptyTracker = TestAssetTracker()
            val emptyOps = AssetViewSearchOperations(emptyTracker)

            val request = TextSimilaritySearchRequest(
                query = "anything",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = emptyOps.textSearch(request, AssetRetrievable::class.java)

            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class VectorSearchTest {

        private lateinit var tracker: TestAssetTracker
        private lateinit var embeddingService: EmbeddingService
        private lateinit var ops: AssetViewSearchOperations

        // Pre-defined embeddings for predictable similarity
        private val kotlinEmbedding = floatArrayOf(1f, 0f, 0f, 0f).normalize()
        private val javaEmbedding = floatArrayOf(0.7f, 0.7f, 0f, 0f).normalize()
        private val pythonEmbedding = floatArrayOf(0f, 1f, 0f, 0f).normalize()

        @BeforeEach
        fun setup() {
            tracker = TestAssetTracker()
            embeddingService = mockk()
            ops = AssetViewSearchOperations(tracker, embeddingService)

            tracker.addAsset(createAsset("asset-1", "Kotlin Programming", "JVM language"))
            tracker.addAsset(createAsset("asset-2", "Java Programming", "JVM language"))
            tracker.addAsset(createAsset("asset-3", "Python Scripting", "Scripting language"))
        }

        @Test
        fun `vectorSearch returns empty without embedding service`() {
            val noEmbeddingOps = AssetViewSearchOperations(tracker)

            val request = TextSimilaritySearchRequest(
                query = "kotlin",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = noEmbeddingOps.vectorSearch(request, AssetRetrievable::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `vectorSearch returns results sorted by similarity`() {
            every { embeddingService.embed(match<String> { it.contains("Kotlin") }) } returns kotlinEmbedding
            every { embeddingService.embed(match<String> { it.contains("Java") }) } returns javaEmbedding
            every { embeddingService.embed(match<String> { it.contains("Python") }) } returns pythonEmbedding
            every { embeddingService.embed("kotlin query") } returns kotlinEmbedding

            val request = TextSimilaritySearchRequest(
                query = "kotlin query",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = ops.vectorSearch(request, AssetRetrievable::class.java)

            assertEquals(3, results.size)
            // Kotlin asset should be first (identical embedding)
            assertEquals("asset-1", results[0].match.id)
            assertEquals(1.0, results[0].score, 0.01)
            // Java should be second (similar)
            assertEquals("asset-2", results[1].match.id)
            // Python should be last (orthogonal to Kotlin)
            assertEquals("asset-3", results[2].match.id)
        }

        @Test
        fun `vectorSearch respects similarity threshold`() {
            every { embeddingService.embed(any<String>()) } returns pythonEmbedding
            every { embeddingService.embed("kotlin query") } returns kotlinEmbedding

            val request = TextSimilaritySearchRequest(
                query = "kotlin query",
                similarityThreshold = 0.5,
                topK = 10
            )

            val results = ops.vectorSearch(request, AssetRetrievable::class.java)

            // Python embedding is orthogonal to Kotlin, similarity ~0
            assertTrue(results.isEmpty())
        }

        @Test
        fun `vectorSearch respects topK limit`() {
            every { embeddingService.embed(any<String>()) } returns kotlinEmbedding

            val request = TextSimilaritySearchRequest(
                query = "anything",
                similarityThreshold = 0.0,
                topK = 2
            )

            val results = ops.vectorSearch(request, AssetRetrievable::class.java)

            assertEquals(2, results.size)
        }

        @Test
        fun `vectorSearch caches embeddings`() {
            every { embeddingService.embed(any<String>()) } returns kotlinEmbedding

            val request = TextSimilaritySearchRequest(
                query = "test",
                similarityThreshold = 0.0,
                topK = 10
            )

            // First search
            ops.vectorSearch(request, AssetRetrievable::class.java)
            // Second search
            ops.vectorSearch(request, AssetRetrievable::class.java)

            // Asset embeddings should only be computed once (3 assets + 2 queries = 5 calls)
            // Without caching it would be 3 + 3 + 2 = 8 calls
            verify(exactly = 5) { embeddingService.embed(any<String>()) }
        }

        @Test
        fun `clearEmbeddingCache clears cached embeddings`() {
            every { embeddingService.embed(any<String>()) } returns kotlinEmbedding

            val request = TextSimilaritySearchRequest(
                query = "test",
                similarityThreshold = 0.0,
                topK = 10
            )

            // First search - caches embeddings
            ops.vectorSearch(request, AssetRetrievable::class.java)

            // Clear cache
            ops.clearEmbeddingCache()

            // Second search - must recompute embeddings
            ops.vectorSearch(request, AssetRetrievable::class.java)

            // Asset embeddings computed twice (3 + 3 + 2 queries = 8 calls)
            verify(exactly = 8) { embeddingService.embed(any<String>()) }
        }

        @Test
        fun `precomputeEmbeddings caches all asset embeddings`() {
            every { embeddingService.embed(any<String>()) } returns kotlinEmbedding

            ops.precomputeEmbeddings()

            // 3 assets should have embeddings computed
            verify(exactly = 3) { embeddingService.embed(any<String>()) }
        }

        @Test
        fun `precomputeEmbeddings does nothing without embedding service`() {
            val noEmbeddingOps = AssetViewSearchOperations(tracker)

            // Should not throw
            noEmbeddingOps.precomputeEmbeddings()
        }

        private fun FloatArray.normalize(): FloatArray {
            var norm = 0f
            for (v in this) norm += v * v
            norm = kotlin.math.sqrt(norm)
            return if (norm > 0) FloatArray(size) { this[it] / norm } else this
        }
    }

    @Nested
    inner class AssetRetrievableTest {

        @Test
        fun `AssetRetrievable exposes asset id`() {
            val asset = createAsset("test-id", "Test Name", "Test Description")
            val retrievable = AssetRetrievable(asset)

            assertEquals("test-id", retrievable.id)
        }

        @Test
        fun `AssetRetrievable has null uri`() {
            val asset = createAsset("test-id", "Test Name", "Test Description")
            val retrievable = AssetRetrievable(asset)

            assertNull(retrievable.uri)
        }

        @Test
        fun `AssetRetrievable metadata contains name and description`() {
            val asset = createAsset("test-id", "Test Name", "Test Description")
            val retrievable = AssetRetrievable(asset)

            assertEquals("Test Name", retrievable.metadata["name"])
            assertEquals("Test Description", retrievable.metadata["description"])
        }

        @Test
        fun `AssetRetrievable embeddableValue returns contribution`() {
            val asset = createAsset("test-id", "Test Name", "Test Description")
            val retrievable = AssetRetrievable(asset)

            val embeddable = retrievable.embeddableValue()

            assertTrue(embeddable.contains("Test Name"))
            assertTrue(embeddable.contains("Test Description"))
        }

        @Test
        fun `getAsset returns underlying asset`() {
            val asset = createAsset("test-id", "Test Name", "Test Description")
            val retrievable = AssetRetrievable(asset)

            assertSame(asset, retrievable.getAsset())
        }
    }

    // Test helpers

    private fun createAsset(id: String, name: String, description: String): Asset {
        return TestAsset(
            id = id,
            reference = LlmReference.of(
                name = name,
                description = description,
                tools = emptyList(),
                notes = ""
            )
        )
    }

    private class TestAsset(
        override val id: String,
        private val reference: LlmReference,
        override val timestamp: Instant = Instant.now()
    ) : Asset {
        override fun reference(): LlmReference = reference

        override fun persistent(): Boolean = false
    }

    private class TestAssetTracker : AssetTracker {
        private val _assets = mutableListOf<Asset>()

        override fun addAsset(asset: Asset) {
            if (_assets.none { it.id == asset.id }) {
                _assets.add(asset)
            }
        }

        override val assets: List<Asset>
            get() = _assets.toList()
    }
}
