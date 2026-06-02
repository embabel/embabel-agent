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

class EnhancementEstimateTest {

    @Test
    fun `should create EnhancementEstimate with all properties`() {
        // Arrange & Act
        val estimate = EnhancementEstimate(
            expectedQualityGain = 0.85,
            estimatedLatencyMs = 500L,
            estimatedTokenCost = 150,
            recommendation = EnhancementRecommendation.APPLY
        )

        // Assert
        assertEquals(0.85, estimate.expectedQualityGain, 0.001)
        assertEquals(500L, estimate.estimatedLatencyMs)
        assertEquals(150, estimate.estimatedTokenCost)
        assertEquals(EnhancementRecommendation.APPLY, estimate.recommendation)
    }

    @Test
    fun `should create EnhancementEstimate with SKIP recommendation`() {
        // Arrange & Act
        val estimate = EnhancementEstimate(
            expectedQualityGain = 0.1,
            estimatedLatencyMs = 1000L,
            estimatedTokenCost = 500,
            recommendation = EnhancementRecommendation.SKIP
        )

        // Assert
        assertEquals(EnhancementRecommendation.SKIP, estimate.recommendation)
    }

    @Test
    fun `should create EnhancementEstimate with CONDITIONAL recommendation`() {
        // Arrange & Act
        val estimate = EnhancementEstimate(
            expectedQualityGain = 0.5,
            estimatedLatencyMs = 300L,
            estimatedTokenCost = 200,
            recommendation = EnhancementRecommendation.CONDITIONAL
        )

        // Assert
        assertEquals(EnhancementRecommendation.CONDITIONAL, estimate.recommendation)
    }

    @Test
    fun `should support copy with modified expectedQualityGain`() {
        // Arrange
        val original = EnhancementEstimate(
            expectedQualityGain = 0.5,
            estimatedLatencyMs = 200L,
            estimatedTokenCost = 100,
            recommendation = EnhancementRecommendation.APPLY
        )

        // Act
        val modified = original.copy(expectedQualityGain = 0.95)

        // Assert
        assertEquals(0.95, modified.expectedQualityGain, 0.001)
        assertEquals(0.5, original.expectedQualityGain, 0.001)
        assertEquals(200L, modified.estimatedLatencyMs)
    }

    @Test
    fun `should support copy with modified estimatedLatencyMs`() {
        // Arrange
        val original = EnhancementEstimate(
            expectedQualityGain = 0.7,
            estimatedLatencyMs = 100L,
            estimatedTokenCost = 50,
            recommendation = EnhancementRecommendation.APPLY
        )

        // Act
        val modified = original.copy(estimatedLatencyMs = 2000L)

        // Assert
        assertEquals(2000L, modified.estimatedLatencyMs)
        assertEquals(100L, original.estimatedLatencyMs)
    }

    @Test
    fun `should support copy with modified estimatedTokenCost`() {
        // Arrange
        val original = EnhancementEstimate(
            expectedQualityGain = 0.6,
            estimatedLatencyMs = 300L,
            estimatedTokenCost = 100,
            recommendation = EnhancementRecommendation.CONDITIONAL
        )

        // Act
        val modified = original.copy(estimatedTokenCost = 500)

        // Assert
        assertEquals(500, modified.estimatedTokenCost)
        assertEquals(100, original.estimatedTokenCost)
    }

    @Test
    fun `should support copy with modified recommendation`() {
        // Arrange
        val original = EnhancementEstimate(
            expectedQualityGain = 0.8,
            estimatedLatencyMs = 400L,
            estimatedTokenCost = 200,
            recommendation = EnhancementRecommendation.APPLY
        )

        // Act
        val modified = original.copy(recommendation = EnhancementRecommendation.SKIP)

        // Assert
        assertEquals(EnhancementRecommendation.SKIP, modified.recommendation)
        assertEquals(EnhancementRecommendation.APPLY, original.recommendation)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val estimate1 = EnhancementEstimate(0.75, 250L, 120, EnhancementRecommendation.APPLY)
        val estimate2 = EnhancementEstimate(0.75, 250L, 120, EnhancementRecommendation.APPLY)
        val estimate3 = EnhancementEstimate(0.8, 250L, 120, EnhancementRecommendation.APPLY)

        // Assert
        assertEquals(estimate1, estimate2)
        assertNotEquals(estimate1, estimate3)
    }

    @Test
    fun `should support component functions`() {
        // Arrange
        val estimate = EnhancementEstimate(0.9, 600L, 300, EnhancementRecommendation.CONDITIONAL)

        // Act
        val (qualityGain, latency, tokenCost, recommendation) = estimate

        // Assert
        assertEquals(0.9, qualityGain, 0.001)
        assertEquals(600L, latency)
        assertEquals(300, tokenCost)
        assertEquals(EnhancementRecommendation.CONDITIONAL, recommendation)
    }

    @Test
    fun `should handle zero quality gain`() {
        // Arrange & Act
        val estimate = EnhancementEstimate(
            expectedQualityGain = 0.0,
            estimatedLatencyMs = 100L,
            estimatedTokenCost = 50,
            recommendation = EnhancementRecommendation.SKIP
        )

        // Assert
        assertEquals(0.0, estimate.expectedQualityGain, 0.001)
    }

    @Test
    fun `should handle negative quality gain`() {
        // Arrange & Act
        val estimate = EnhancementEstimate(
            expectedQualityGain = -0.2,
            estimatedLatencyMs = 100L,
            estimatedTokenCost = 50,
            recommendation = EnhancementRecommendation.SKIP
        )

        // Assert
        assertEquals(-0.2, estimate.expectedQualityGain, 0.001)
    }

    @Test
    fun `should handle large latency values`() {
        // Arrange & Act
        val estimate = EnhancementEstimate(
            expectedQualityGain = 0.5,
            estimatedLatencyMs = 30000L,
            estimatedTokenCost = 10000,
            recommendation = EnhancementRecommendation.CONDITIONAL
        )

        // Assert
        assertEquals(30000L, estimate.estimatedLatencyMs)
        assertEquals(10000, estimate.estimatedTokenCost)
    }

    @Test
    fun `hashCode should be consistent for equal objects`() {
        // Arrange
        val estimate1 = EnhancementEstimate(0.65, 350L, 175, EnhancementRecommendation.APPLY)
        val estimate2 = EnhancementEstimate(0.65, 350L, 175, EnhancementRecommendation.APPLY)

        // Act & Assert
        assertEquals(estimate1.hashCode(), estimate2.hashCode())
    }

    @Test
    fun `toString should contain field values`() {
        // Arrange
        val estimate = EnhancementEstimate(0.88, 450L, 225, EnhancementRecommendation.CONDITIONAL)

        // Act
        val string = estimate.toString()

        // Assert
        assertTrue(string.contains("0.88"))
        assertTrue(string.contains("450"))
        assertTrue(string.contains("225"))
        assertTrue(string.contains("CONDITIONAL"))
    }
}
