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
package com.embabel.agent.rag.tools

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.service.*
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertNull

class RagServiceSearchToolsTest {

    @Test
    fun `should create RagServiceTools`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)

        val ragTools = SingleShotRagServiceSearchTools(options)

        assertEquals(options, ragTools.options)
        assertEquals(SimpleRetrievableResultsFormatter, ragTools.options.retrievableResultsFormatter)
    }

    @Test
    fun `should search with default options`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)
        val ragTools = SingleShotRagServiceSearchTools(options)

        val mockChunk = mockk<Chunk>()
        every { mockChunk.id } returns "chunk-1"
        every { mockChunk.text } returns "Test chunk content"
        every { mockChunk.uri } returns null
        every { mockChunk.infoString(any(), any()) } returns "Test chunk info"

        val searchResults = listOf(
            SimpleSimilaritySearchResult(match = mockChunk, score = 0.9)
        )
        val mockResponse = RagResponse(RagRequest("test"), "test-service", searchResults)

        every { mockRagService.search(any()) } returns mockResponse

        val result = ragTools.search("test query")

        assertTrue(result.startsWith("1 results:"))
        assertTrue(result.contains("0.9"))
        assertTrue(result.contains("Test chunk content"))
        verify { mockRagService.search(any()) }
    }

    @Test
    fun `should search with empty results`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)
        val ragTools = SingleShotRagServiceSearchTools(options)

        val mockResponse = RagResponse(RagRequest("test query"), "test-service", emptyList())
        every { mockRagService.search(any()) } returns mockResponse

        val result = ragTools.search("test query")

        assertEquals("0 results:", result)
        verify { mockRagService.search(any()) }
    }

    @Test
    fun `should pass correct parameters to RagService search`() {
        val entitySearch = EntitySearch(setOf("label1", "label2"))
        val mockRagService = mockk<RagService>()
        val options = RagOptions(
            ragService = mockRagService,
            similarityThreshold = 0.8,
            topK = 5,
            entitySearch = entitySearch,
        )
        val ragTools = SingleShotRagServiceSearchTools(options)

        val mockResponse = RagResponse(RagRequest("test query"), "test-service", emptyList())
        every { mockRagService.search(any()) } returns mockResponse

        ragTools.search("test query")

        verify {
            mockRagService.search(match<RagRequest> { request ->
                request.query == "test query" &&
                        request.similarityThreshold == 0.8 &&
                        request.topK == 5 &&
                        request.entitySearch == entitySearch
            })
        }
    }

    @Test
    fun `should create default RagServiceToolsOptions`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)

        assertEquals(0.7, options.similarityThreshold.toDouble(), 0.001)
        assertEquals(8, options.topK)
        assertNull(options.entitySearch)
        assertEquals(SimpleRetrievableResultsFormatter, options.retrievableResultsFormatter)
    }

    @Test
    fun `should create RagServiceToolsOptions with custom values`() {
        val mockRagService = mockk<RagService>()
        val entitySearch = EntitySearch(setOf("custom-label"))
        val customFormatter = mockk<RetrievableResultsFormatter>()
        val options = RagOptions(
            ragService = mockRagService,
            similarityThreshold = 0.9,
            topK = 10,
            entitySearch = entitySearch,
            retrievableResultsFormatter = customFormatter
        )

        assertEquals(0.9, options.similarityThreshold.toDouble(), 0.001)
        assertEquals(10, options.topK)
        assertEquals(entitySearch, options.entitySearch)
        assertEquals(customFormatter, options.retrievableResultsFormatter)
    }

    @Test
    fun `should update similarityThreshold using withSimilarityThreshold`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)
        val newThreshold = 0.9

        val updatedOptions = options.withSimilarityThreshold(newThreshold)

        assertEquals(newThreshold, updatedOptions.similarityThreshold)
        assertEquals(options.topK, updatedOptions.topK)
        assertEquals(options.entitySearch, updatedOptions.entitySearch)
        assertEquals(options.retrievableResultsFormatter, updatedOptions.retrievableResultsFormatter)
    }

    @Test
    fun `should update topK using withTopK`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)
        val newTopK = 15

        val updatedOptions = options.withTopK(newTopK)

        assertEquals(options.similarityThreshold, updatedOptions.similarityThreshold)
        assertEquals(newTopK, updatedOptions.topK)
        assertEquals(options.entitySearch, updatedOptions.entitySearch)
        assertEquals(options.retrievableResultsFormatter, updatedOptions.retrievableResultsFormatter)
    }

    @Test
    fun `should chain option modifications`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)

        val updatedOptions = options
            .withSimilarityThreshold(0.95)
            .withTopK(20)

        assertEquals(0.95, updatedOptions.similarityThreshold.toDouble(), 0.001)
        assertEquals(20, updatedOptions.topK)
        assertEquals(options.entitySearch, updatedOptions.entitySearch)
    }

    @Test
    fun `should use custom formatter when provided in options`() {
        val mockRagService = mockk<RagService>()
        val customFormatter = mockk<RetrievableResultsFormatter>()
        val options = RagOptions(mockRagService, retrievableResultsFormatter = customFormatter)
        val ragTools = SingleShotRagServiceSearchTools(options)

        val mockResponse = RagResponse(RagRequest("test query"), "test-service", emptyList())
        every { mockRagService.search(any()) } returns mockResponse
        every { customFormatter.formatResults(any()) } returns "Custom formatted response"

        val result = ragTools.search("test query")

        assertEquals("Custom formatted response", result)
        verify { customFormatter.formatResults(mockResponse) }
    }

    @Test
    fun `should handle multiple search results with different types`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(mockRagService)
        val ragTools = SingleShotRagServiceSearchTools(options)

        val mockChunk1 = mockk<Chunk>()
        every { mockChunk1.id } returns "chunk-1"
        every { mockChunk1.text } returns "First chunk"
        every { mockChunk1.uri } returns null
        every { mockChunk1.infoString(any(), any()) } returns "First chunk info"

        val mockChunk2 = mockk<Chunk>()
        every { mockChunk2.id } returns "chunk-2"
        every { mockChunk2.text } returns "Second chunk"
        every { mockChunk2.uri } returns null
        every { mockChunk2.infoString(any(), any()) } returns "Second chunk info"

        val searchResults = listOf(
            SimpleSimilaritySearchResult(match = mockChunk1, score = 0.95),
            SimpleSimilaritySearchResult(match = mockChunk2, score = 0.85)
        )
        val mockResponse = RagResponse(RagRequest("test query"), "test-service", searchResults)

        every { mockRagService.search(any()) } returns mockResponse

        val result = ragTools.search("test query")

        assertTrue(result.contains("0.95"))
        assertTrue(result.contains("First chunk"))
        assertTrue(result.contains("0.85"))
        assertTrue(result.contains("Second chunk"))
        assertTrue(result.contains("\n---\n")) // Results should be separated by ---
    }

    @Test
    fun `should validate RagServiceToolsOptions implements RagRequestRefinement`() {
        val options = RagOptions(
            ragService = mockk<RagService>(),
            similarityThreshold = 0.85,
            topK = 12,
            entitySearch = EntitySearch(setOf("test-label"))

        )

        // Test that it properly implements RagRequestRefinement interface
        val request = options.toRequest("test query")

        assertEquals("test query", request.query)
        assertEquals(0.85, request.similarityThreshold)
        assertEquals(12, request.topK)
        assertEquals(setOf("test-label"), request.entitySearch?.labels)
    }
}
