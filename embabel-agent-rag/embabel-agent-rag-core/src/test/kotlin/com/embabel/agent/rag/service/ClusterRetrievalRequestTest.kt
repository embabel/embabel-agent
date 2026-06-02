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
package com.embabel.agent.rag.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClusterRetrievalRequestTest {

    @Test
    fun `should create with default values`() {
        // Arrange & Act
        val request = ClusterRetrievalRequest<String>()

        // Assert
        assertEquals(0.7, request.similarityThreshold, 0.001)
        assertEquals(10, request.topK)
        assertEquals("embabel-entity-index", request.vectorIndex)
    }

    @Test
    fun `should create with custom values`() {
        // Arrange & Act
        val request = ClusterRetrievalRequest<String>(
            similarityThreshold = 0.85,
            topK = 20,
            vectorIndex = "custom-index"
        )

        // Assert
        assertEquals(0.85, request.similarityThreshold, 0.001)
        assertEquals(20, request.topK)
        assertEquals("custom-index", request.vectorIndex)
    }

    @Test
    fun `should support withSimilarityThreshold`() {
        // Arrange
        val original = ClusterRetrievalRequest<String>()

        // Act
        val modified = original.withSimilarityThreshold(0.9)

        // Assert
        assertEquals(0.9, modified.similarityThreshold, 0.001)
        assertEquals(0.7, original.similarityThreshold, 0.001)
    }

    @Test
    fun `should support withTopK`() {
        // Arrange
        val original = ClusterRetrievalRequest<String>()

        // Act
        val modified = original.withTopK(50)

        // Assert
        assertEquals(50, modified.topK)
        assertEquals(10, original.topK)
    }

    @Test
    fun `should support copy with modified similarityThreshold`() {
        // Arrange
        val original = ClusterRetrievalRequest<String>(similarityThreshold = 0.7)

        // Act
        val modified = original.copy(similarityThreshold = 0.95)

        // Assert
        assertEquals(0.95, modified.similarityThreshold, 0.001)
        assertEquals(0.7, original.similarityThreshold, 0.001)
    }

    @Test
    fun `should support copy with modified topK`() {
        // Arrange
        val original = ClusterRetrievalRequest<String>(topK = 10)

        // Act
        val modified = original.copy(topK = 100)

        // Assert
        assertEquals(100, modified.topK)
        assertEquals(10, original.topK)
    }

    @Test
    fun `should support copy with modified vectorIndex`() {
        // Arrange
        val original = ClusterRetrievalRequest<String>(vectorIndex = "original-index")

        // Act
        val modified = original.copy(vectorIndex = "new-index")

        // Assert
        assertEquals("new-index", modified.vectorIndex)
        assertEquals("original-index", original.vectorIndex)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val request1 = ClusterRetrievalRequest<String>(
            similarityThreshold = 0.8,
            topK = 15,
            vectorIndex = "test-index"
        )
        val request2 = ClusterRetrievalRequest<String>(
            similarityThreshold = 0.8,
            topK = 15,
            vectorIndex = "test-index"
        )
        val request3 = ClusterRetrievalRequest<String>(
            similarityThreshold = 0.9,
            topK = 15,
            vectorIndex = "test-index"
        )

        // Assert
        assertEquals(request1, request2)
        assertNotEquals(request1, request3)
    }

    @Test
    fun `should chain withSimilarityThreshold and withTopK`() {
        // Arrange
        val original = ClusterRetrievalRequest<String>()

        // Act
        val modified = original
            .withSimilarityThreshold(0.95)
            .withTopK(25)

        // Assert
        assertEquals(0.95, modified.similarityThreshold, 0.001)
        assertEquals(25, modified.topK)
        assertEquals("embabel-entity-index", modified.vectorIndex)
    }

    @Test
    fun `should handle minimum similarity threshold`() {
        // Arrange & Act
        val request = ClusterRetrievalRequest<String>(similarityThreshold = 0.0)

        // Assert
        assertEquals(0.0, request.similarityThreshold, 0.001)
    }

    @Test
    fun `should handle maximum similarity threshold`() {
        // Arrange & Act
        val request = ClusterRetrievalRequest<String>(similarityThreshold = 1.0)

        // Assert
        assertEquals(1.0, request.similarityThreshold, 0.001)
    }

    @Test
    fun `should handle small topK`() {
        // Arrange & Act
        val request = ClusterRetrievalRequest<String>(topK = 1)

        // Assert
        assertEquals(1, request.topK)
    }

    @Test
    fun `should handle large topK`() {
        // Arrange & Act
        val request = ClusterRetrievalRequest<String>(topK = 1000)

        // Assert
        assertEquals(1000, request.topK)
    }

    @Test
    fun `should work with different generic types`() {
        // Arrange & Act
        val stringRequest = ClusterRetrievalRequest<String>()
        val intRequest = ClusterRetrievalRequest<Int>()

        // Assert
        assertNotNull(stringRequest)
        assertNotNull(intRequest)
    }
}
