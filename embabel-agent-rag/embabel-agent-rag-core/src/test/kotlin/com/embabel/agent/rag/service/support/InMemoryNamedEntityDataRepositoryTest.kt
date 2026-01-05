/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InMemoryNamedEntityDataRepositoryTest {

    private val emptyDictionary = DataDictionary.fromClasses()

    @Nested
    inner class BasicOperationsTest {

        private lateinit var repository: InMemoryNamedEntityDataRepository

        @BeforeEach
        fun setup() {
            repository = InMemoryNamedEntityDataRepository(emptyDictionary)
        }

        @Test
        fun `save and findById work correctly`() {
            val entity = SimpleNamedEntityData(
                id = "test-1",
                name = "Test Entity",
                description = "A test entity",
                labels = setOf("Test"),
                properties = mapOf("key" to "value")
            )

            repository.save(entity)

            val result = repository.findById("test-1")
            assertNotNull(result)
            assertEquals("test-1", result!!.id)
            assertEquals("Test Entity", result.name)
        }

        @Test
        fun `findById returns null for missing entity`() {
            val result = repository.findById("nonexistent")
            assertNull(result)
        }

        @Test
        fun `delete removes entity`() {
            val entity = SimpleNamedEntityData(
                id = "delete-1",
                name = "Delete Me",
                description = "To be deleted",
                labels = setOf("Test"),
                properties = emptyMap()
            )
            repository.save(entity)

            val deleted = repository.delete("delete-1")

            assertTrue(deleted)
            assertNull(repository.findById("delete-1"))
        }

        @Test
        fun `delete returns false for missing entity`() {
            val deleted = repository.delete("nonexistent")
            assertFalse(deleted)
        }

        @Test
        fun `findByLabel returns matching entities`() {
            repository.save(SimpleNamedEntityData(
                id = "person-1",
                name = "Alice",
                description = "A person",
                labels = setOf("Person"),
                properties = emptyMap()
            ))
            repository.save(SimpleNamedEntityData(
                id = "person-2",
                name = "Bob",
                description = "Another person",
                labels = setOf("Person", "Manager"),
                properties = emptyMap()
            ))
            repository.save(SimpleNamedEntityData(
                id = "org-1",
                name = "Acme",
                description = "A company",
                labels = setOf("Organization"),
                properties = emptyMap()
            ))

            val persons = repository.findByLabel("Person")
            assertEquals(2, persons.size)

            val managers = repository.findByLabel("Manager")
            assertEquals(1, managers.size)
            assertEquals("Bob", managers.first().name)
        }

        @Test
        fun `clear removes all entities`() {
            repository.save(SimpleNamedEntityData(
                id = "clear-1",
                name = "Entity 1",
                description = "First",
                labels = setOf("Test"),
                properties = emptyMap()
            ))
            repository.save(SimpleNamedEntityData(
                id = "clear-2",
                name = "Entity 2",
                description = "Second",
                labels = setOf("Test"),
                properties = emptyMap()
            ))

            assertEquals(2, repository.size)

            repository.clear()

            assertEquals(0, repository.size)
            assertNull(repository.findById("clear-1"))
        }

        @Test
        fun `vectorSearch returns empty without embedding service`() {
            repository.save(SimpleNamedEntityData(
                id = "vec-1",
                name = "Vector Entity",
                description = "For vector search",
                labels = setOf("Test"),
                properties = emptyMap()
            ))

            val request = TextSimilaritySearchRequest(
                query = "vector",
                similarityThreshold = 0.5,
                topK = 10
            )

            val results = repository.vectorSearch(request)
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class TextSearchTest {

        private lateinit var repository: InMemoryNamedEntityDataRepository

        @BeforeEach
        fun setup() {
            repository = InMemoryNamedEntityDataRepository(emptyDictionary)
            repository.save(SimpleNamedEntityData(
                id = "kotlin-1",
                name = "Kotlin Programming",
                description = "A modern programming language",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))
            repository.save(SimpleNamedEntityData(
                id = "java-1",
                name = "Java Programming",
                description = "A classic programming language",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))
            repository.save(SimpleNamedEntityData(
                id = "go-1",
                name = "Go",
                description = "A systems programming language by Google",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))
        }

        @Test
        fun `textSearch finds matches in name`() {
            val request = TextSimilaritySearchRequest(
                query = "kotlin",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = repository.textSearch(request)

            assertEquals(1, results.size)
            assertEquals("kotlin-1", results.first().match.id)
        }

        @Test
        fun `textSearch finds matches in description`() {
            val request = TextSimilaritySearchRequest(
                query = "Google",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = repository.textSearch(request)

            assertEquals(1, results.size)
            assertEquals("go-1", results.first().match.id)
        }

        @Test
        fun `textSearch is case insensitive`() {
            val request = TextSimilaritySearchRequest(
                query = "PROGRAMMING",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = repository.textSearch(request)

            assertEquals(3, results.size)
        }

        @Test
        fun `textSearch respects topK limit`() {
            val request = TextSimilaritySearchRequest(
                query = "programming",
                similarityThreshold = 0.0,
                topK = 2
            )

            val results = repository.textSearch(request)

            assertEquals(2, results.size)
        }

        @Test
        fun `textSearch returns empty for no matches`() {
            val request = TextSimilaritySearchRequest(
                query = "python",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = repository.textSearch(request)

            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class VectorSearchTest {

        private lateinit var embeddingService: EmbeddingService
        private lateinit var repository: InMemoryNamedEntityDataRepository

        // Pre-defined embeddings for predictable similarity calculations
        private val kotlinEmbedding = floatArrayOf(1f, 0f, 0f, 0f).normalize()
        private val javaEmbedding = floatArrayOf(0.7f, 0.7f, 0f, 0f).normalize()
        private val pythonEmbedding = floatArrayOf(0f, 1f, 0f, 0f).normalize()
        private val queryEmbedding = floatArrayOf(0.9f, 0.1f, 0f, 0f).normalize()

        @BeforeEach
        fun setup() {
            embeddingService = mockk()
            repository = InMemoryNamedEntityDataRepository(
                dataDictionary = emptyDictionary,
                embeddingService = embeddingService
            )
        }

        @Test
        fun `vectorSearch returns results sorted by similarity`() {
            // Setup embeddings - kotlin entity is more similar to query than java entity
            every { embeddingService.embed(match<String> { it.contains("Kotlin") }) } returns kotlinEmbedding
            every { embeddingService.embed(match<String> { it.contains("Java") }) } returns javaEmbedding

            repository.save(SimpleNamedEntityData(
                id = "entity-1",
                name = "Kotlin coroutines",
                description = "Async programming in Kotlin",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))
            repository.save(SimpleNamedEntityData(
                id = "entity-2",
                name = "Java threads",
                description = "Concurrency in Java",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))

            val request = TextSimilaritySearchRequest(
                query = "Kotlin async",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = repository.vectorSearch(request)

            assertEquals(2, results.size)
            // Kotlin should be more similar to query (both use kotlinEmbedding)
            assertEquals("entity-1", results[0].match.id)
            assertTrue(results[0].score > results[1].score)
        }

        @Test
        fun `vectorSearch respects similarity threshold`() {
            every { embeddingService.embed(any<String>()) } returns pythonEmbedding

            repository.save(SimpleNamedEntityData(
                id = "python-1",
                name = "Python programming",
                description = "Python language",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))

            // Use kotlin embedding for query - orthogonal to python, so similarity ~0
            every { embeddingService.embed("kotlin query") } returns kotlinEmbedding

            val request = TextSimilaritySearchRequest(
                query = "kotlin query",
                similarityThreshold = 0.5,
                topK = 10
            )

            val results = repository.vectorSearch(request)

            // Orthogonal vectors have ~0 similarity, should be filtered by threshold
            assertTrue(results.isEmpty())
        }

        @Test
        fun `vectorSearch respects topK limit`() {
            every { embeddingService.embed(any<String>()) } returns kotlinEmbedding

            repeat(5) { i ->
                repository.save(SimpleNamedEntityData(
                    id = "entity-$i",
                    name = "Entity $i",
                    description = "Description $i",
                    labels = setOf("Topic"),
                    properties = emptyMap()
                ))
            }

            val request = TextSimilaritySearchRequest(
                query = "entity",
                similarityThreshold = 0.0,
                topK = 3
            )

            val results = repository.vectorSearch(request)

            assertEquals(3, results.size)
        }

        @Test
        fun `delete removes embedding`() {
            every { embeddingService.embed(any<String>()) } returns kotlinEmbedding

            repository.save(SimpleNamedEntityData(
                id = "to-delete",
                name = "Delete Me",
                description = "Should be deleted",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))

            repository.delete("to-delete")

            val request = TextSimilaritySearchRequest(
                query = "delete",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = repository.vectorSearch(request)
            assertTrue(results.isEmpty())
        }

        @Test
        fun `clear removes all embeddings`() {
            every { embeddingService.embed(any<String>()) } returns kotlinEmbedding

            repository.save(SimpleNamedEntityData(
                id = "entity-1",
                name = "First",
                description = "First entity",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))
            repository.save(SimpleNamedEntityData(
                id = "entity-2",
                name = "Second",
                description = "Second entity",
                labels = setOf("Topic"),
                properties = emptyMap()
            ))

            repository.clear()

            val request = TextSimilaritySearchRequest(
                query = "entity",
                similarityThreshold = 0.0,
                topK = 10
            )

            val results = repository.vectorSearch(request)
            assertTrue(results.isEmpty())
        }

        private fun FloatArray.normalize(): FloatArray {
            var norm = 0f
            for (v in this) norm += v * v
            norm = kotlin.math.sqrt(norm)
            return if (norm > 0) FloatArray(size) { this[it] / norm } else this
        }
    }
}
