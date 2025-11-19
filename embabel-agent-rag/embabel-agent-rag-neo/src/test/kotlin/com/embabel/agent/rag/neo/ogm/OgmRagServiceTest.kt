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
package com.embabel.agent.rag.neo.ogm

import com.embabel.agent.rag.model.*
import com.embabel.agent.rag.neo.support.NeoIntegrationTestSupport
import com.embabel.agent.rag.service.ClusterRetrievalRequest
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.TypedEntitySearch
import com.embabel.common.ai.model.Llm
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.neo4j.ogm.annotation.Id
import org.neo4j.ogm.annotation.NodeEntity
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisabledIfEnvironmentVariable(named = "SKIP_TESTCONTAINER_TESTS", matches = "true")
class OgmRagServiceTest(
    @param:Autowired @param:Qualifier("best")
    private val cypherGenerationLlm: Llm,
) : NeoIntegrationTestSupport() {

    @BeforeEach
    fun setup() {
        ragService.provision()
    }

    companion object {
        fun fakeContent(): MaterializedDocument {
            val rootId = "whatever"
            val sec1Id = "sec1"

            val leaf1 = LeafSection(
                id = "leaf1",
                title = "Leaf 1",
                text = "This is the content of leaf 1.",
                parentId = sec1Id
            )
            val sec1 = DefaultMaterializedContainerSection(
                id = sec1Id,
                title = "Section 1",
                parentId = rootId,
                children = listOf(leaf1),
            )
            return MaterializedDocument(
                id = rootId,
                title = "great",
                ingestionTimestamp = java.time.Instant.now(),
                children = listOf(sec1),
                uri = "file:///great"
            )
        }
    }

    @NodeEntity
    data class Dog(
        @Id val id: String,
        val name: String,
    ) : Embeddable {
        override fun embeddableValue(): String {
            return name
        }
    }

    @Nested
    inner class VectorClusteringTest {

        @Test
        fun `clustering empty `() {

            val entityClusters = ogmCypherSearch.findClusters<Dog>(
                ClusterRetrievalRequest(TypedEntitySearch(listOf(Dog::class.java)))
            )
            assertTrue(entityClusters.isEmpty(), "Expected no entity clusters")
        }
    }


    @Nested
    inner class FullTextSearchTest {
        @Test
        fun `should find chunk by full-text search`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)

            val results = ragService.search(
                RagRequest("leaf").withSimilarityThreshold(.0)
            )

            assertTrue(results.results.isNotEmpty(), "Expected results from full-text search")
            val chunkResult = results.results.find { it.match is Chunk }
            assertTrue(chunkResult != null, "Expected to find chunk in results")
            assertTrue((chunkResult!!.match as Chunk).text.contains("leaf"), "Expected chunk to contain search term")
        }

        @Test
        fun `should find chunk by exact text match`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)

            val results = ragService.search(
                RagRequest("content of leaf 1").withSimilarityThreshold(.0)
            )

            assertTrue(results.results.isNotEmpty(), "Expected results from full-text search for exact match")
            val chunkResult = results.results.find { it.match is Chunk }
            assertTrue(chunkResult != null, "Expected to find chunk in results")
        }

        @Test
        fun `full-text search should work with low similarity threshold`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)

            val results = ragService.search(
                RagRequest("leaf").withSimilarityThreshold(0.1)
            )

            assertTrue(results.results.isNotEmpty(), "Expected results with low similarity threshold")
        }
    }

    @Nested
    inner class HybridSearchTest {
        @Test
        fun `hybrid search should combine vector and full-text results`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)

            val results = ragService.search(
                RagRequest("leaf").withSimilarityThreshold(.0)
            )

            assertTrue(results.results.isNotEmpty(), "Expected results from hybrid search")

            // The search should find chunks through multiple methods
            // Log the number of results to understand what's happening
            logger.info("Hybrid search returned {} results", results.results.size)
            results.results.forEach { result ->
                logger.info(
                    "Result: {} - {}", result.match.javaClass.simpleName,
                    if (result.match is Chunk) (result.match as Chunk).text.take(50) + "..." else result.match.toString()
                )
            }

            val chunkResults = results.results.filter { it.match is Chunk }
            assertTrue(chunkResults.isNotEmpty(), "Expected at least one chunk from hybrid search")
        }

        @Test
        fun `hybrid search should not duplicate results`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)

            val results = ragService.search(
                RagRequest("leaf").withSimilarityThreshold(.0)
            )

            // Check that results are deduplicated by ID
            val ids = results.results.map { it.match.id }
            val uniqueIds = ids.distinct()

            assertEquals(ids.size, uniqueIds.size, "Expected no duplicate results in hybrid search")
        }
    }

    @Nested
    inner class SmokeTest {
        @Test
        fun `should find nothing in empty db`() {
            every { cypherGenerationLlm.model.call(any<String>()) } returns "MATCH (n) WHERE n.name CONTAINS 'test' RETURN n"

            val results = ragService.search(
                RagRequest(
                    query = "test",
                    topK = 10,
                )
            )
            assertEquals(0, results.results.size, "Expected no results in empty database")
        }
    }


    @Nested
    inner class WriteContentTest {

        @Test
        fun `write content`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)
            val results = ragService.findAll()
            assertEquals(4, results.size, "Expected 3 nodes (root, section, leaf) plus one chunk")
        }

        @Test
        fun `chunks are embedded`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)
            val chunks = ragService.findAll().filterIsInstance<Chunk>()
            assertTrue(chunks.isNotEmpty(), "Expected chunks to be extracted")
            logger.info("Chunks: {}", chunks)
            val chunkCount = ogmCypherSearch.queryForInt("MATCH (c:Chunk) RETURN count(c) AS count")

            assertEquals(
                chunks.size,
                chunkCount,
                "Expected chunk count to match"
            )
            val emptyChunkCount =
                ogmCypherSearch.queryForInt("MATCH (c:Chunk) WHERE c.embedding IS NULL RETURN count(c) AS count")
            assertEquals(0, emptyChunkCount, "Expected all chunks to have embeddings")
        }

        @Test
        fun `chunks have parents`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)
            val chunks = ragService.findAll().filterIsInstance<Chunk>()
            assertTrue(chunks.isNotEmpty(), "Expected chunks to be extracted")
            logger.info("Chunks: {}", chunks)
            chunks.forEach {
                assertTrue(it.parentId != null, "Expected chunk to have a parent: $it")
            }
        }

        @Test
        fun `families are together`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)
            val chunks = ragService.findAll().filterIsInstance<Chunk>()
            assertTrue(chunks.isNotEmpty(), "Expected chunks to be extracted")
            val orphanCount = driver().session().executeRead { tx ->
                tx.run(
                    """
                    MATCH (c:Chunk)
                    WHERE c.parent IS NOT NULL
                      AND NOT EXISTS((c)-[:HAS_PARENT]->())
                    RETURN count(c) AS count
                """.trimIndent()
                )
                    .single().get("count").asLong()
            }
            assertEquals(0, orphanCount, "Expected no orphans. Orphans make me sad")
        }

        @Test
        @Disabled
        fun `single chunk is retrieved`() {
            val mcr = fakeContent()
            ragService.writeAndChunkDocument(mcr)
            val chunks = ragService.findAll().filterIsInstance<Chunk>()
            assertEquals(1, chunks.size, "Expected a single chunk to be created")
            val results = ragService.search(RagRequest("anything at all").withSimilarityThreshold(.0))
            assertEquals(1, results.results.size, "Expected one chunk to be retrieved")
            val r1 = results.results[0]
            assertTrue(r1 is Chunk, "Expected result to be a Chunk")
            assertTrue(r1.text.contains("leaf 1"), "Expected chunk to contain text from leaf 1")
        }

    }

    @Nested
    inner class IngestionDatePersistenceTests {

        @Test
        fun `should persist and retrieve ingestionDate for MaterializedDocument`() {
            val testTime = java.time.Instant.parse("2025-01-15T10:30:00Z")
            val document = MaterializedDocument(
                id = "test-doc-1",
                uri = "test://document-with-date",
                title = "Test Document",
                ingestionTimestamp = testTime,
                children = emptyList()
            )

            ragService.writeAndChunkDocument(document)

            // Retrieve the document and verify ingestionDate
            val retrieved = ragService.findById("test-doc-1")
            assertNotNull(retrieved)
            assertTrue(retrieved is ContentRoot)

            val contentRoot = retrieved as ContentRoot
            assertEquals(testTime, contentRoot.ingestionTimestamp)
        }

        @Test
        fun `should persist ingestionDate in propertiesToPersist`() {
            val testTime = java.time.Instant.parse("2025-02-20T15:45:30Z")
            val document = MaterializedDocument(
                id = "test-doc-2",
                uri = "test://document-properties",
                title = "Properties Test",
                ingestionTimestamp = testTime,
                children = emptyList()
            )

            val properties = document.propertiesToPersist()

            assertTrue(properties.containsKey("ingestionTimestamp"))
            assertEquals(testTime, properties["ingestionTimestamp"])
            assertEquals("Properties Test", properties["title"])
        }

        @Test
        fun `should handle documents with default ingestionDate`() {
            // Create document without explicit ingestionDate (uses default)
            val beforeCreation = java.time.Instant.now()
            val document = MaterializedDocument(
                id = "test-doc-3",
                uri = "test://document-default-date",
                title = "Default Date Test",
                children = emptyList()
            )
            val afterCreation = java.time.Instant.now()

            ragService.writeAndChunkDocument(document)

            val retrieved = ragService.findById("test-doc-3") as? ContentRoot
            assertNotNull(retrieved)

            // Should be between before and after creation
            assertTrue(
                retrieved!!.ingestionTimestamp >= beforeCreation && retrieved.ingestionTimestamp <= afterCreation,
                "Expected ingestionDate to be between $beforeCreation and $afterCreation but was ${retrieved.ingestionTimestamp}"
            )
        }

        @Test
        fun `should preserve different ingestionDates for multiple documents`() {
            val time1 = java.time.Instant.parse("2025-01-01T00:00:00Z")
            val time2 = java.time.Instant.parse("2025-06-15T12:00:00Z")
            val time3 = java.time.Instant.parse("2025-12-31T23:59:59Z")

            val doc1 = MaterializedDocument(
                id = "doc-1",
                uri = "test://doc1",
                title = "Document 1",
                ingestionTimestamp = time1,
                children = emptyList()
            )
            val doc2 = MaterializedDocument(
                id = "doc-2",
                uri = "test://doc2",
                title = "Document 2",
                ingestionTimestamp = time2,
                children = emptyList()
            )
            val doc3 = MaterializedDocument(
                id = "doc-3",
                uri = "test://doc3",
                title = "Document 3",
                ingestionTimestamp = time3,
                children = emptyList()
            )

            ragService.writeAndChunkDocument(doc1)
            ragService.writeAndChunkDocument(doc2)
            ragService.writeAndChunkDocument(doc3)

            val retrieved1 = ragService.findById("doc-1") as ContentRoot
            val retrieved2 = ragService.findById("doc-2") as ContentRoot
            val retrieved3 = ragService.findById("doc-3") as ContentRoot

            assertEquals(time1, retrieved1.ingestionTimestamp)
            assertEquals(time2, retrieved2.ingestionTimestamp)
            assertEquals(time3, retrieved3.ingestionTimestamp)
        }

        @Test
        fun `should persist ingestionDate for nested sections`() {
            val rootTime = java.time.Instant.parse("2025-03-01T10:00:00Z")

            val leaf = LeafSection(
                id = "leaf-1",
                title = "Leaf Section",
                text = "Content",
                parentId = "section-1"
            )

            val section = DefaultMaterializedContainerSection(
                id = "section-1",
                title = "Container Section",
                children = listOf(leaf),
                parentId = "root-1"
            )

            val document = MaterializedDocument(
                id = "root-1",
                uri = "test://nested-document",
                title = "Root Document",
                ingestionTimestamp = rootTime,
                children = listOf(section)
            )

            ragService.writeAndChunkDocument(document)

            val retrievedRoot = ragService.findById("root-1") as ContentRoot
            assertEquals(rootTime, retrievedRoot.ingestionTimestamp)
        }
    }
}
