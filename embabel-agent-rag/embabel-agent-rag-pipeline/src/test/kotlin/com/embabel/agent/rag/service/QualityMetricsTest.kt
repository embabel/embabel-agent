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

class QualityMetricsTest {

    @Test
    fun `should create QualityMetrics with all metrics`() {
        // Arrange & Act
        val metrics = QualityMetrics(
            faithfulness = 0.9,
            answerRelevancy = 0.85,
            contextPrecision = 0.88,
            contextRecall = 0.92,
            contextRelevancy = 0.87
        )

        // Assert
        assertEquals(0.9, metrics.faithfulness, 0.001)
        assertEquals(0.85, metrics.answerRelevancy, 0.001)
        assertEquals(0.88, metrics.contextPrecision, 0.001)
        assertEquals(0.92, metrics.contextRecall, 0.001)
        assertEquals(0.87, metrics.contextRelevancy, 0.001)
        assertTrue(metrics.overallScore > 0.0)
    }

    @Test
    fun `should compute overall score automatically`() {
        // Arrange & Act
        val metrics = QualityMetrics(
            faithfulness = 0.8,
            answerRelevancy = 0.8,
            contextPrecision = 0.8,
            contextRecall = 0.8,
            contextRelevancy = 0.8
        )

        // Assert
        assertEquals(0.8, metrics.overallScore, 0.001)
    }

    @Test
    fun `should support copy with modified faithfulness`() {
        // Arrange
        val original = QualityMetrics(
            faithfulness = 0.9,
            answerRelevancy = 0.85,
            contextPrecision = 0.88,
            contextRecall = 0.92,
            contextRelevancy = 0.87
        )

        // Act
        val modified = original.copy(faithfulness = 0.95)

        // Assert
        assertEquals(0.95, modified.faithfulness, 0.001)
        assertEquals(0.9, original.faithfulness, 0.001)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val metrics1 = QualityMetrics(
            faithfulness = 0.9,
            answerRelevancy = 0.85,
            contextPrecision = 0.88,
            contextRecall = 0.92,
            contextRelevancy = 0.87
        )
        val metrics2 = QualityMetrics(
            faithfulness = 0.9,
            answerRelevancy = 0.85,
            contextPrecision = 0.88,
            contextRecall = 0.92,
            contextRelevancy = 0.87
        )
        val metrics3 = QualityMetrics(
            faithfulness = 0.8,
            answerRelevancy = 0.85,
            contextPrecision = 0.88,
            contextRecall = 0.92,
            contextRelevancy = 0.87
        )

        // Assert
        assertEquals(metrics1.faithfulness, metrics2.faithfulness, 0.001)
        assertNotEquals(metrics1.faithfulness, metrics3.faithfulness, 0.001)
    }

    @Test
    fun `should handle perfect scores`() {
        // Arrange & Act
        val metrics = QualityMetrics(
            faithfulness = 1.0,
            answerRelevancy = 1.0,
            contextPrecision = 1.0,
            contextRecall = 1.0,
            contextRelevancy = 1.0
        )

        // Assert
        assertEquals(1.0, metrics.overallScore, 0.001)
    }

    @Test
    fun `should handle zero scores`() {
        // Arrange & Act
        val metrics = QualityMetrics(
            faithfulness = 0.0,
            answerRelevancy = 0.0,
            contextPrecision = 0.0,
            contextRecall = 0.0,
            contextRelevancy = 0.0
        )

        // Assert
        assertEquals(0.0, metrics.overallScore, 0.001)
    }

    @Test
    fun `should compute RAGAS score using harmonic mean`() {
        // Arrange & Act
        val score = computeRAGASScore(
            faithfulness = 0.9,
            answerRelevancy = 0.8,
            contextPrecision = 0.85,
            contextRecall = 0.75,
            contextRelevancy = 0.88
        )

        // Assert - harmonic mean penalizes low values more than arithmetic mean
        assertTrue(score > 0.0)
        assertTrue(score < 0.9) // Should be lower than the highest value
    }

    @Test
    fun `should compute RAGAS score for equal values`() {
        // Arrange & Act
        val score = computeRAGASScore(
            faithfulness = 0.8,
            answerRelevancy = 0.8,
            contextPrecision = 0.8,
            contextRecall = 0.8,
            contextRelevancy = 0.8
        )

        // Assert
        assertEquals(0.8, score, 0.001)
    }

    @Test
    fun `should handle zero values in RAGAS score computation`() {
        // Arrange & Act
        val score = computeRAGASScore(
            faithfulness = 0.0,
            answerRelevancy = 0.8,
            contextPrecision = 0.8,
            contextRecall = 0.8,
            contextRelevancy = 0.8
        )

        // Assert - with one zero value, should only use non-zero values
        assertTrue(score > 0.0)
    }

    @Test
    fun `should handle all zero values in RAGAS score computation`() {
        // Arrange & Act
        val score = computeRAGASScore(
            faithfulness = 0.0,
            answerRelevancy = 0.0,
            contextPrecision = 0.0,
            contextRecall = 0.0,
            contextRelevancy = 0.0
        )

        // Assert
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun `should allow custom overall score`() {
        // Arrange & Act
        val metrics = QualityMetrics(
            faithfulness = 0.9,
            answerRelevancy = 0.85,
            contextPrecision = 0.88,
            contextRecall = 0.92,
            contextRelevancy = 0.87,
            overallScore = 0.75
        )

        // Assert
        assertEquals(0.75, metrics.overallScore, 0.001)
    }
}
