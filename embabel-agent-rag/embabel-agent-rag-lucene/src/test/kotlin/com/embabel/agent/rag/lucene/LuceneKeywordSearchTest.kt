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
package com.embabel.agent.rag.lucene

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * Tests for keyword search functionality in LuceneSearchOperations.
 */
class LuceneKeywordSearchTest {

    private lateinit var ragService: LuceneSearchOperations

    @BeforeEach
    fun setUp() {
        ragService = createTestRagService()
    }

    @AfterEach
    fun tearDown() {
        ragService.close()
    }

    @Test
    fun `should find chunks by keyword intersection with provided keywords`() {
        val documents = listOf(
            Document(
                "doc1", "This document discusses cars and speed limits on highways",
                mapOf("keywords" to listOf("cars", "speed", "highways"))
            ),
            Document(
                "doc2", "Pedestrians must obey traffic signals and speed limits",
                mapOf("keywords" to listOf("pedestrians", "speed", "signals"))
            ),
            Document(
                "doc3", "Cars should yield to pedestrians at crosswalks",
                mapOf("keywords" to listOf("cars", "pedestrians", "crosswalks"))
            ),
            Document(
                "doc4", "Weather forecast for tomorrow",
                mapOf("keywords" to listOf("weather", "forecast"))
            )
        )

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("cars", "pedestrians", "speed"),
            minIntersection = 1
        )

        assertTrue(results.isNotEmpty())

        val doc2Result = results.find { it.first == "doc2" }
        assertNotNull(doc2Result)
        assertEquals(2, doc2Result!!.second, "doc2 should match 2 keywords")

        val doc3Result = results.find { it.first == "doc3" }
        assertNotNull(doc3Result)
        assertEquals(2, doc3Result!!.second, "doc3 should match 2 keywords")

        val resultsMin2 = ragService.findChunkIdsByKeywords(
            keywords = setOf("cars", "pedestrians", "speed"),
            minIntersection = 2
        )
        val doc4InResults = resultsMin2.any { it.first == "doc4" }
        assertFalse(doc4InResults, "doc4 should not match 2+ keywords")
    }

    @Test
    fun `should find chunks by provided keywords in metadata`() {
        val documents = listOf(
            Document(
                "doc1",
                "Some content about automotive safety",
                mapOf("keywords" to listOf("car", "safety", "speedlimit"))
            ),
            Document(
                "doc2",
                "Different content about traffic",
                mapOf("keywords" to listOf("pedestrian", "crosswalk", "speedlimit"))
            ),
            Document(
                "doc3",
                "Another topic entirely",
                mapOf("keywords" to listOf("weather", "forecast"))
            )
        )

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("car", "pedestrian", "speedlimit"),
            minIntersection = 1
        )

        assertTrue(results.size >= 2)
        val foundIds = results.map { it.first }.toSet()
        assertTrue(foundIds.contains("doc1"))
        assertTrue(foundIds.contains("doc2"))

        val doc1Match = results.find { it.first == "doc1" }
        assertEquals(2, doc1Match!!.second)

        val doc2Match = results.find { it.first == "doc2" }
        assertEquals(2, doc2Match!!.second)
    }

    @Test
    fun `should return empty list when no keywords match`() {
        val documents = listOf(
            Document("doc1", "Content about something", emptyMap<String, Any>())
        )

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("nonexistent", "keywords", "here"),
            minIntersection = 1
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `should respect maxResults parameter`() {
        val documents = (1..20).map { i ->
            Document(
                "doc$i",
                "This document is about cars and transportation",
                emptyMap<String, Any>()
            )
        }

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("cars", "transportation"),
            minIntersection = 1,
            maxResults = 5
        )

        assertTrue(results.size <= 5)
    }

    @Test
    fun `should sort results by match count descending`() {
        val documents = listOf(
            Document("doc1", "car", mapOf("keywords" to "car")),
            Document("doc2", "car pedestrian", mapOf("keywords" to listOf("car", "pedestrian"))),
            Document(
                "doc3",
                "car pedestrian speedlimit",
                mapOf("keywords" to listOf("car", "pedestrian", "speedlimit"))
            )
        )

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("car", "pedestrian", "speedlimit"),
            minIntersection = 1
        )

        assertEquals("doc3", results[0].first)
        assertEquals(3, results[0].second)

        assertEquals("doc2", results[1].first)
        assertEquals(2, results[1].second)

        assertEquals("doc1", results[2].first)
        assertEquals(1, results[2].second)
    }

    @Test
    fun `should retrieve chunks by IDs from keyword search`() {
        val documents = listOf(
            Document(
                "ml-doc",
                "Machine learning algorithms",
                mapOf("keywords" to listOf("machine", "learning", "algorithms"))
            ),
            Document(
                "ai-doc",
                "Artificial intelligence systems",
                mapOf("keywords" to listOf("artificial", "intelligence", "systems"))
            ),
            Document(
                "ds-doc",
                "Data science and machine learning",
                mapOf("keywords" to listOf("data", "science", "machine", "learning"))
            )
        )

        ragService.acceptDocuments(documents)

        val keywordResults = ragService.findChunkIdsByKeywords(
            keywords = setOf("machine", "learning"),
            minIntersection = 2
        )

        assertEquals(2, keywordResults.size)
        val chunkIds = keywordResults.map { it.first }

        val chunks = ragService.findAllChunksById(chunkIds)
        assertEquals(2, chunks.size)

        val chunkTexts = chunks.map { it.text }
        assertTrue(chunkTexts.any { it.contains("Machine learning algorithms") })
        assertTrue(chunkTexts.any { it.contains("Data science and machine learning") })
    }

    @Test
    fun `should handle empty keyword set`() {
        val documents = listOf(
            Document("doc1", "Some content", emptyMap<String, Any>())
        )

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = emptySet(),
            minIntersection = 1
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `should not find chunks without keywords in metadata`() {
        val documents = listOf(
            Document("doc1", "Machine learning is a subset of artificial intelligence", emptyMap<String, Any>())
        )

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("machine", "learning", "artificial"),
            minIntersection = 1
        )

        assertTrue(results.isEmpty(), "Should not find chunks without keywords in metadata")
    }

    @Test
    fun `should update keywords for existing chunks`() {
        val documents = listOf(
            Document("doc1", "Traffic content", mapOf("keywords" to listOf("traffic", "roads"))),
            Document("doc2", "Weather content", mapOf("keywords" to listOf("weather", "forecast")))
        )

        ragService.acceptDocuments(documents)

        val initialResults = ragService.findChunkIdsByKeywords(
            keywords = setOf("traffic"),
            minIntersection = 1
        )
        assertEquals(1, initialResults.size)
        assertEquals("doc1", initialResults[0].first)

        ragService.updateKeywords(
            mapOf("doc1" to listOf("car", "pedestrian", "speedlimit"))
        )

        val afterUpdateOldKeywords = ragService.findChunkIdsByKeywords(
            keywords = setOf("traffic"),
            minIntersection = 1
        )
        assertTrue(afterUpdateOldKeywords.isEmpty(), "Old keywords should not match after update")

        val afterUpdateNewKeywords = ragService.findChunkIdsByKeywords(
            keywords = setOf("car", "pedestrian"),
            minIntersection = 1
        )
        assertEquals(1, afterUpdateNewKeywords.size)
        assertEquals("doc1", afterUpdateNewKeywords[0].first)
        assertEquals(2, afterUpdateNewKeywords[0].second)

        val updatedChunk = ragService.findAllChunksById(listOf("doc1"))[0]
        val keywords = updatedChunk.metadata["keywords"]
        assertNotNull(keywords)
        @Suppress("UNCHECKED_CAST")
        val keywordList = keywords as List<String>
        assertEquals(setOf("car", "pedestrian", "speedlimit"), keywordList.toSet())
    }

    @Test
    fun `should handle updating non-existent chunk keywords gracefully`() {
        ragService.updateKeywords(
            mapOf("non-existent-id" to listOf("keyword1", "keyword2"))
        )

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("keyword1"),
            minIntersection = 1
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `should maintain correct count after updates that create deleted documents`() {
        val documents = listOf(
            Document("doc1", "Traffic content", mapOf("keywords" to listOf("traffic", "roads"))),
            Document("doc2", "Weather content", mapOf("keywords" to listOf("weather", "forecast"))),
            Document("doc3", "Sports content", mapOf("keywords" to listOf("sports", "football")))
        )

        ragService.acceptDocuments(documents)

        assertEquals(3, ragService.info().contentElementCount)
        val initialStats = ragService.info()
        assertEquals(3, initialStats.chunkCount)
        assertEquals(0, initialStats.documentCount)

        ragService.updateKeywords(
            mapOf(
                "doc1" to listOf("car", "pedestrian", "speedlimit"),
                "doc2" to listOf("rain", "sun", "temperature")
            )
        )

        assertEquals(3, ragService.info().contentElementCount, "Count should remain 3 after keyword updates")
        val afterUpdateStats = ragService.info()
        assertEquals(3, afterUpdateStats.chunkCount, "Total chunks should remain 3")
        assertEquals(
            0,
            afterUpdateStats.documentCount,
            "documentCount should be 0 (only Chunks, no NavigableDocuments)"
        )

        val allChunks = ragService.findAll()
        assertEquals(3, allChunks.size, "findAll should return exactly 3 chunks")
        assertEquals(setOf("doc1", "doc2", "doc3"), allChunks.map { it.id }.toSet())
    }
}
