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

class EnhancementRecommendationTest {

    @Test
    fun `should have APPLY value`() {
        // Act
        val recommendation = EnhancementRecommendation.APPLY

        // Assert
        assertEquals("APPLY", recommendation.name)
    }

    @Test
    fun `should have SKIP value`() {
        // Act
        val recommendation = EnhancementRecommendation.SKIP

        // Assert
        assertEquals("SKIP", recommendation.name)
    }

    @Test
    fun `should have CONDITIONAL value`() {
        // Act
        val recommendation = EnhancementRecommendation.CONDITIONAL

        // Assert
        assertEquals("CONDITIONAL", recommendation.name)
    }

    @Test
    fun `should have exactly 3 values`() {
        // Act
        val values = EnhancementRecommendation.values()

        // Assert
        assertEquals(3, values.size)
    }

    @Test
    fun `should support valueOf for APPLY`() {
        // Act
        val recommendation = EnhancementRecommendation.valueOf("APPLY")

        // Assert
        assertEquals(EnhancementRecommendation.APPLY, recommendation)
    }

    @Test
    fun `should support valueOf for SKIP`() {
        // Act
        val recommendation = EnhancementRecommendation.valueOf("SKIP")

        // Assert
        assertEquals(EnhancementRecommendation.SKIP, recommendation)
    }

    @Test
    fun `should support valueOf for CONDITIONAL`() {
        // Act
        val recommendation = EnhancementRecommendation.valueOf("CONDITIONAL")

        // Assert
        assertEquals(EnhancementRecommendation.CONDITIONAL, recommendation)
    }

    @Test
    fun `should throw exception for invalid valueOf`() {
        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            EnhancementRecommendation.valueOf("INVALID")
        }
    }

    @Test
    fun `should support equality comparison`() {
        // Arrange
        val recommendation1 = EnhancementRecommendation.APPLY
        val recommendation2 = EnhancementRecommendation.APPLY
        val recommendation3 = EnhancementRecommendation.SKIP

        // Assert
        assertEquals(recommendation1, recommendation2)
        assertNotEquals(recommendation1, recommendation3)
    }

    @Test
    fun `should support toString`() {
        // Act
        val apply = EnhancementRecommendation.APPLY.toString()
        val skip = EnhancementRecommendation.SKIP.toString()
        val conditional = EnhancementRecommendation.CONDITIONAL.toString()

        // Assert
        assertEquals("APPLY", apply)
        assertEquals("SKIP", skip)
        assertEquals("CONDITIONAL", conditional)
    }

    @Test
    fun `should support ordinal values`() {
        // Act
        val apply = EnhancementRecommendation.APPLY
        val skip = EnhancementRecommendation.SKIP
        val conditional = EnhancementRecommendation.CONDITIONAL

        // Assert
        assertEquals(0, apply.ordinal)
        assertEquals(1, skip.ordinal)
        assertEquals(2, conditional.ordinal)
    }

    @Test
    fun `values should contain all enum constants`() {
        // Act
        val values = EnhancementRecommendation.values()

        // Assert
        assertTrue(values.contains(EnhancementRecommendation.APPLY))
        assertTrue(values.contains(EnhancementRecommendation.SKIP))
        assertTrue(values.contains(EnhancementRecommendation.CONDITIONAL))
    }

    @Test
    fun `should support when expressions`() {
        // Arrange
        val recommendations = listOf(
            EnhancementRecommendation.APPLY,
            EnhancementRecommendation.SKIP,
            EnhancementRecommendation.CONDITIONAL
        )

        // Act & Assert
        recommendations.forEach { recommendation ->
            when (recommendation) {
                EnhancementRecommendation.APPLY -> assertEquals("APPLY", recommendation.name)
                EnhancementRecommendation.SKIP -> assertEquals("SKIP", recommendation.name)
                EnhancementRecommendation.CONDITIONAL -> assertEquals("CONDITIONAL", recommendation.name)
            }
        }
    }

    @Test
    fun `should support identity equality`() {
        // Arrange
        val apply1 = EnhancementRecommendation.APPLY
        val apply2 = EnhancementRecommendation.APPLY

        // Assert
        assertSame(apply1, apply2)
    }

    @Test
    fun `hashCode should be consistent`() {
        // Arrange
        val apply1 = EnhancementRecommendation.APPLY
        val apply2 = EnhancementRecommendation.APPLY

        // Act & Assert
        assertEquals(apply1.hashCode(), apply2.hashCode())
    }
}
