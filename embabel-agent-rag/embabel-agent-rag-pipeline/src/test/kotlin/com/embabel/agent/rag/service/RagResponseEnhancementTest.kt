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

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RagResponseEnhancementTest {

    @Test
    fun `should create RagResponseEnhancement with all properties`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()

        // Act
        val enhancement = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 1500L,
            tokensProcessed = 250,
            enhancementType = EnhancementType.COMPRESSION,
            qualityImpact = 0.85
        )

        // Assert
        assertEquals(mockEnhancer, enhancement.enhancer)
        assertEquals(mockResponse, enhancement.basis)
        assertEquals(1500L, enhancement.processingTimeMs)
        assertEquals(250, enhancement.tokensProcessed)
        assertEquals(EnhancementType.COMPRESSION, enhancement.enhancementType)
        assertEquals(0.85, enhancement.qualityImpact!!, 0.001)
    }

    @Test
    fun `should create RagResponseEnhancement with default values`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()

        // Act
        val enhancement = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            enhancementType = EnhancementType.RERANKING
        )

        // Assert
        assertEquals(0L, enhancement.processingTimeMs)
        assertEquals(0, enhancement.tokensProcessed)
        assertNull(enhancement.qualityImpact)
    }

    @Test
    fun `should create RagResponseEnhancement with null qualityImpact`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()

        // Act
        val enhancement = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 500L,
            tokensProcessed = 100,
            enhancementType = EnhancementType.DEDUPLICATION,
            qualityImpact = null
        )

        // Assert
        assertNull(enhancement.qualityImpact)
    }

    @Test
    fun `should support copy with modified processingTimeMs`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()
        val original = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 1000L,
            tokensProcessed = 100,
            enhancementType = EnhancementType.ENTITY_EXTRACTION
        )

        // Act
        val modified = original.copy(processingTimeMs = 2000L)

        // Assert
        assertEquals(2000L, modified.processingTimeMs)
        assertEquals(1000L, original.processingTimeMs)
    }

    @Test
    fun `should support copy with modified tokensProcessed`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()
        val original = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            tokensProcessed = 50,
            enhancementType = EnhancementType.FACT_CHECKING
        )

        // Act
        val modified = original.copy(tokensProcessed = 150)

        // Assert
        assertEquals(150, modified.tokensProcessed)
        assertEquals(50, original.tokensProcessed)
    }

    @Test
    fun `should support copy with modified enhancementType`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()
        val original = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            enhancementType = EnhancementType.QUALITY_ASSESSMENT
        )

        // Act
        val modified = original.copy(enhancementType = EnhancementType.CONTENT_SYNTHESIS)

        // Assert
        assertEquals(EnhancementType.CONTENT_SYNTHESIS, modified.enhancementType)
        assertEquals(EnhancementType.QUALITY_ASSESSMENT, original.enhancementType)
    }

    @Test
    fun `should support copy with modified qualityImpact`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()
        val original = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            enhancementType = EnhancementType.MULTIMODAL_PROCESSING,
            qualityImpact = 0.75
        )

        // Act
        val modified = original.copy(qualityImpact = 0.95)

        // Assert
        assertEquals(0.95, modified.qualityImpact!!, 0.001)
        assertEquals(0.75, original.qualityImpact!!, 0.001)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()
        val enhancement1 = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 500L,
            tokensProcessed = 75,
            enhancementType = EnhancementType.CUSTOM
        )
        val enhancement2 = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 500L,
            tokensProcessed = 75,
            enhancementType = EnhancementType.CUSTOM
        )

        // Assert
        assertEquals(enhancement1, enhancement2)
    }

    @Test
    fun `should handle zero processing time`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()

        // Act
        val enhancement = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 0L,
            tokensProcessed = 0,
            enhancementType = EnhancementType.COMPRESSION
        )

        // Assert
        assertEquals(0L, enhancement.processingTimeMs)
        assertEquals(0, enhancement.tokensProcessed)
    }

    @Test
    fun `should handle large processing time and token values`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()

        // Act
        val enhancement = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 30000L,
            tokensProcessed = 10000,
            enhancementType = EnhancementType.RERANKING,
            qualityImpact = 0.99
        )

        // Assert
        assertEquals(30000L, enhancement.processingTimeMs)
        assertEquals(10000, enhancement.tokensProcessed)
    }

    @Test
    fun `should handle different enhancementType values`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()

        // Act & Assert
        EnhancementType.values().forEach { type ->
            val enhancement = RagResponseEnhancement(
                enhancer = mockEnhancer,
                basis = mockResponse,
                enhancementType = type
            )
            assertEquals(type, enhancement.enhancementType)
        }
    }

    @Test
    fun `should handle qualityImpact at boundaries`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()

        // Act
        val zeroImpact = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            enhancementType = EnhancementType.COMPRESSION,
            qualityImpact = 0.0
        )
        val maxImpact = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            enhancementType = EnhancementType.COMPRESSION,
            qualityImpact = 1.0
        )

        // Assert
        assertEquals(0.0, zeroImpact.qualityImpact!!, 0.001)
        assertEquals(1.0, maxImpact.qualityImpact!!, 0.001)
    }

    @Test
    fun `hashCode should be consistent for equal objects`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()
        val enhancement1 = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 800L,
            tokensProcessed = 120,
            enhancementType = EnhancementType.DEDUPLICATION,
            qualityImpact = 0.88
        )
        val enhancement2 = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 800L,
            tokensProcessed = 120,
            enhancementType = EnhancementType.DEDUPLICATION,
            qualityImpact = 0.88
        )

        // Act & Assert
        assertEquals(enhancement1.hashCode(), enhancement2.hashCode())
    }

    @Test
    fun `toString should contain field values`() {
        // Arrange
        val mockEnhancer = mockk<RagResponseEnhancer>()
        val mockResponse = mockk<RagResponse>()
        val enhancement = RagResponseEnhancement(
            enhancer = mockEnhancer,
            basis = mockResponse,
            processingTimeMs = 1200L,
            tokensProcessed = 200,
            enhancementType = EnhancementType.CONTENT_SYNTHESIS,
            qualityImpact = 0.92
        )

        // Act
        val string = enhancement.toString()

        // Assert
        assertTrue(string.contains("1200"))
        assertTrue(string.contains("200"))
        assertTrue(string.contains("CONTENT_SYNTHESIS"))
    }
}
