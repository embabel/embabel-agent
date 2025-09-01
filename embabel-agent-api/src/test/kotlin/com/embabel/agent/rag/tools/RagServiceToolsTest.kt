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
package com.embabel.agent.rag.tools

import com.embabel.agent.rag.*
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RagServiceToolsTest {

    @Test
    fun `should create RagServiceTools with invoke operator`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions()

        val ragTools = RagServiceTools(mockRagService, options)

        assertEquals(mockRagService, ragTools.ragService)
        assertEquals(options, ragTools.options)
        assertEquals(SimpleRagResponseFormatter, ragTools.options.ragResponseFormatter)
    }

    @Test
    fun `should create RagServiceTools with static create method`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions()

        val ragTools = RagServiceTools.create(mockRagService, options)

        assertEquals(mockRagService, ragTools.ragService)
        assertEquals(options, ragTools.options)
    }

    @Test
    fun `should search with default options`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions()
        val ragTools = RagServiceTools(mockRagService, options)

        val mockChunk = mockk<Chunk>()
        every { mockChunk.text } returns "Test chunk content"
        every { mockChunk.infoString(any(), any()) } returns "Test chunk info"

        val searchResults = listOf(
            SimpleSimilaritySearchResult(match = mockChunk, score = 0.9)
        )
        val mockResponse = RagResponse(RagRequest("test"), "test-service", searchResults)

        every { mockRagService.search(any()) } returns mockResponse

        val result = ragTools.search("test query")

        assertTrue(result.contains("0.9"))
        assertTrue(result.contains("Test chunk content"))
        verify { mockRagService.search(any()) }
    }

    @Test
    fun `should search with empty results`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions()
        val ragTools = RagServiceTools(mockRagService, options)

        val mockResponse = RagResponse(RagRequest("test query"), "test-service", emptyList())
        every { mockRagService.search(any()) } returns mockResponse

        val result = ragTools.search("test query")

        assertEquals(SimpleRagResponseFormatter.NO_RESULTS_FOUND, result)
        verify { mockRagService.search(any()) }
    }

    @Test
    fun `should pass correct parameters to RagService search`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions(
            similarityThreshold = 0.8,
            topK = 5,
            labels = setOf("label1", "label2")
        )
        val ragTools = RagServiceTools(mockRagService, options)

        val mockResponse = RagResponse(RagRequest("test query"), "test-service", emptyList())
        every { mockRagService.search(any()) } returns mockResponse

        ragTools.search("test query")

        verify {
            mockRagService.search(match<RagRequest> { request ->
                request.query == "test query" &&
                        request.similarityThreshold == 0.8 &&
                        request.topK == 5 &&
                        request.labels == setOf("label1", "label2")
            })
        }
    }

    @Test
    fun `should create default RagServiceToolsOptions`() {
        val options = RagOptions()

        assertEquals(0.7, options.similarityThreshold.toDouble(), 0.001)
        assertEquals(8, options.topK)
        assertTrue(options.labels.isEmpty())
        assertEquals(SimpleRagResponseFormatter, options.ragResponseFormatter)
    }

    @Test
    fun `should create RagServiceToolsOptions with custom values`() {
        val customFormatter = mockk<RagResponseFormatter>()
        val options = RagOptions(
            similarityThreshold = 0.9,
            topK = 10,
            labels = setOf("custom-label"),
            ragResponseFormatter = customFormatter
        )

        assertEquals(0.9, options.similarityThreshold.toDouble(), 0.001)
        assertEquals(10, options.topK)
        assertEquals(setOf("custom-label"), options.labels)
        assertEquals(customFormatter, options.ragResponseFormatter)
    }

    @Test
    fun `should update similarityThreshold using withSimilarityThreshold`() {
        val options = RagOptions()
        val newThreshold = 0.9

        val updatedOptions = options.withSimilarityThreshold(newThreshold)

        assertEquals(newThreshold, updatedOptions.similarityThreshold)
        assertEquals(options.topK, updatedOptions.topK)
        assertEquals(options.labels, updatedOptions.labels)
        assertEquals(options.ragResponseFormatter, updatedOptions.ragResponseFormatter)
    }

    @Test
    fun `should update topK using withTopK`() {
        val options = RagOptions()
        val newTopK = 15

        val updatedOptions = options.withTopK(newTopK)

        assertEquals(options.similarityThreshold, updatedOptions.similarityThreshold)
        assertEquals(newTopK, updatedOptions.topK)
        assertEquals(options.labels, updatedOptions.labels)
        assertEquals(options.ragResponseFormatter, updatedOptions.ragResponseFormatter)
    }

    @Test
    fun `should chain option modifications`() {
        val options = RagOptions()

        val updatedOptions = options
            .withSimilarityThreshold(0.95)
            .withTopK(20)

        assertEquals(0.95, updatedOptions.similarityThreshold.toDouble(), 0.001)
        assertEquals(20, updatedOptions.topK)
        assertEquals(options.labels, updatedOptions.labels)
    }

    @Test
    fun `should use custom formatter when provided in options`() {
        val mockRagService = mockk<RagService>()
        val customFormatter = mockk<RagResponseFormatter>()
        val options = RagOptions(ragResponseFormatter = customFormatter)
        val ragTools = RagServiceTools(mockRagService, options)

        val mockResponse = RagResponse(RagRequest("test query"), "test-service", emptyList())
        every { mockRagService.search(any()) } returns mockResponse
        every { customFormatter.format(any()) } returns "Custom formatted response"

        val result = ragTools.search("test query")

        assertEquals("Custom formatted response", result)
        verify { customFormatter.format(mockResponse) }
    }

    @Test
    fun `should handle multiple search results with different types`() {
        val mockRagService = mockk<RagService>()
        val options = RagOptions()
        val ragTools = RagServiceTools(mockRagService, options)

        val mockChunk1 = mockk<Chunk>()
        every { mockChunk1.text } returns "First chunk"
        every { mockChunk1.infoString(any(), any()) } returns "First chunk info"

        val mockChunk2 = mockk<Chunk>()
        every { mockChunk2.text } returns "Second chunk"
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
        assertTrue(result.contains("\n\n")) // Results should be separated by double newlines
    }

    @Test
    fun `should validate RagServiceToolsOptions implements RagRequestRefinement`() {
        val options = RagOptions(
            similarityThreshold = 0.85,
            topK = 12,
            labels = setOf("test-label")
        )

        // Test that it properly implements RagRequestRefinement interface
        val request = options.toRequest("test query")

        assertEquals("test query", request.query)
        assertEquals(0.85, request.similarityThreshold)
        assertEquals(12, request.topK)
        assertEquals(setOf("test-label"), request.labels)
    }
}
