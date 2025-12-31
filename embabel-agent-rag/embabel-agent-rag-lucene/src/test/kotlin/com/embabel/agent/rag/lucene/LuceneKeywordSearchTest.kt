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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * Tests for keyword search functionality in LuceneSearchOperations.
 */
class LuceneKeywordSearchTest : LuceneSearchOperationsTestBase() {

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

        // Search for documents with keywords: cars, pedestrians, speed
        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("cars", "pedestrians", "speed"),
            minIntersection = 1
        )

        // All documents with at least one keyword should be found
        assertTrue(results.isNotEmpty())

        // doc2 should match (pedestrians + speed = 2)
        val doc2Result = results.find { it.first == "doc2" }
        assertNotNull(doc2Result)
        assertEquals(2, doc2Result!!.second, "doc2 should match 2 keywords")

        // doc3 should match (cars + pedestrians = 2)
        val doc3Result = results.find { it.first == "doc3" }
        assertNotNull(doc3Result)
        assertEquals(2, doc3Result!!.second, "doc3 should match 2 keywords")

        // doc4 should not be in results with minIntersection=2
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

        // Search for documents with specific keywords
        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("car", "pedestrian", "speedlimit"),
            minIntersection = 1
        )

        // doc1 and doc2 should be found
        assertTrue(results.size >= 2)
        val foundIds = results.map { it.first }.toSet()
        assertTrue(foundIds.contains("doc1"))
        assertTrue(foundIds.contains("doc2"))

        // Check match counts
        val doc1Match = results.find { it.first == "doc1" }
        assertEquals(2, doc1Match!!.second) // car + speedlimit

        val doc2Match = results.find { it.first == "doc2" }
        assertEquals(2, doc2Match!!.second) // pedestrian + speedlimit
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
            Document("doc1", "car", mapOf("keywords" to "car")), // 1 match
            Document("doc2", "car pedestrian", mapOf("keywords" to listOf("car", "pedestrian"))), // 2 matches
            Document(
                "doc3",
                "car pedestrian speedlimit",
                mapOf("keywords" to listOf("car", "pedestrian", "speedlimit"))
            ) // 3 matches
        )

        ragService.acceptDocuments(documents)

        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("car", "pedestrian", "speedlimit"),
            minIntersection = 1
        )

        // Results should be sorted by match count descending
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

        // Find chunks by keywords
        val keywordResults = ragService.findChunkIdsByKeywords(
            keywords = setOf("machine", "learning"),
            minIntersection = 2
        )

        // Should find ml-doc and ds-doc (both have machine + learning)
        assertEquals(2, keywordResults.size)
        val chunkIds = keywordResults.map { it.first }

        // Now load the actual chunks
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

        // Search for keywords that were not provided
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

        // Verify initial keywords work
        val initialResults = ragService.findChunkIdsByKeywords(
            keywords = setOf("traffic"),
            minIntersection = 1
        )
        assertEquals(1, initialResults.size)
        assertEquals("doc1", initialResults[0].first)

        // Update keywords for doc1
        ragService.updateKeywords(
            mapOf("doc1" to listOf("car", "pedestrian", "speedlimit"))
        )

        // Old keywords should not find doc1
        val afterUpdateOldKeywords = ragService.findChunkIdsByKeywords(
            keywords = setOf("traffic"),
            minIntersection = 1
        )
        assertTrue(afterUpdateOldKeywords.isEmpty(), "Old keywords should not match after update")

        // New keywords should find doc1
        val afterUpdateNewKeywords = ragService.findChunkIdsByKeywords(
            keywords = setOf("car", "pedestrian"),
            minIntersection = 1
        )
        assertEquals(1, afterUpdateNewKeywords.size)
        assertEquals("doc1", afterUpdateNewKeywords[0].first)
        assertEquals(2, afterUpdateNewKeywords[0].second)

        // Verify keywords are in chunk metadata
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

        // Should not throw, just log warning
        val results = ragService.findChunkIdsByKeywords(
            keywords = setOf("keyword1"),
            minIntersection = 1
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `should maintain correct count after updates that create deleted documents`() {
        // Add initial documents
        val documents = listOf(
            Document("doc1", "Traffic content", mapOf("keywords" to listOf("traffic", "roads"))),
            Document("doc2", "Weather content", mapOf("keywords" to listOf("weather", "forecast"))),
            Document("doc3", "Sports content", mapOf("keywords" to listOf("sports", "football")))
        )

        ragService.acceptDocuments(documents)

        // Verify initial count
        assertEquals(3, ragService.info().contentElementCount)
        val initialStats = ragService.info()
        assertEquals(3, initialStats.chunkCount)
        assertEquals(0, initialStats.documentCount) // acceptDocuments creates Chunks, not NavigableDocuments

        // Update keywords for doc1 and doc2 (this deletes old documents and adds new ones)
        ragService.updateKeywords(
            mapOf(
                "doc1" to listOf("car", "pedestrian", "speedlimit"),
                "doc2" to listOf("rain", "sun", "temperature")
            )
        )

        // Count should still be 3 (not 5 due to deleted docs)
        assertEquals(3, ragService.info().contentElementCount, "Count should remain 3 after keyword updates")
        val afterUpdateStats = ragService.info()
        assertEquals(3, afterUpdateStats.chunkCount, "Total chunks should remain 3")
        assertEquals(
            0,
            afterUpdateStats.documentCount,
            "documentCount should be 0 (only Chunks, no NavigableDocuments)"
        )

        // Verify all chunks are still accessible
        val allChunks = ragService.findAll()
        assertEquals(3, allChunks.size, "findAll should return exactly 3 chunks")
        assertEquals(setOf("doc1", "doc2", "doc3"), allChunks.map { it.id }.toSet())
    }
}
