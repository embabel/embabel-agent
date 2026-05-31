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
package com.embabel.common.ai.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class KnowledgeCutoffDateTest {

    @Test
    fun `should generate contribution with cutoff date`() {
        // Arrange
        val cutoffDate = LocalDate.of(2024, 6, 1)
        val knowledgeCutoff = KnowledgeCutoffDate(cutoffDate)

        // Act
        val contribution = knowledgeCutoff.contribution()

        // Assert
        assertEquals("Knowledge cutoff: 2024-06\n", contribution)
    }

    @Test
    fun `should use default yyyy-MM format`() {
        // Arrange
        val cutoffDate = LocalDate.of(2023, 12, 31)
        val knowledgeCutoff = KnowledgeCutoffDate(cutoffDate)

        // Act
        val contribution = knowledgeCutoff.contribution()

        // Assert
        assertEquals("Knowledge cutoff: 2023-12\n", contribution)
    }

    @Test
    fun `should use custom formatter`() {
        // Arrange
        val cutoffDate = LocalDate.of(2024, 3, 15)
        val customFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
        val knowledgeCutoff = KnowledgeCutoffDate(cutoffDate, customFormatter)

        // Act
        val contribution = knowledgeCutoff.contribution()

        // Assert
        assertEquals("Knowledge cutoff: March 2024\n", contribution)
    }

    @Test
    fun `should expose date property`() {
        // Arrange
        val cutoffDate = LocalDate.of(2025, 1, 1)
        val knowledgeCutoff = KnowledgeCutoffDate(cutoffDate)

        // Act & Assert
        assertEquals(cutoffDate, knowledgeCutoff.date)
    }

    @Test
    fun `should have correct role`() {
        // Arrange
        val cutoffDate = LocalDate.of(2024, 1, 1)
        val knowledgeCutoff = KnowledgeCutoffDate(cutoffDate)

        // Act
        val role = knowledgeCutoff.role

        // Assert
        assertEquals(PromptContribution.KNOWLEDGE_CUTOFF_ROLE, role)
    }

    @Test
    fun `should have meaningful toString`() {
        // Arrange
        val cutoffDate = LocalDate.of(2024, 5, 1)
        val knowledgeCutoff = KnowledgeCutoffDate(cutoffDate)

        // Act
        val string = knowledgeCutoff.toString()

        // Assert
        assertTrue(string.startsWith("KnowledgeCutoffDate: ["))
        assertTrue(string.contains("Knowledge cutoff:"))
        assertTrue(string.contains("2024-05"))
        assertTrue(string.endsWith("]"))
    }

    @Test
    fun `should implement PromptContributor interface`() {
        // Arrange
        val cutoffDate = LocalDate.of(2024, 1, 1)
        val knowledgeCutoff = KnowledgeCutoffDate(cutoffDate)

        // Assert
        assertTrue(knowledgeCutoff is PromptContributor)
    }

    @Test
    fun `should format different dates correctly`() {
        // Arrange & Act & Assert
        assertEquals(
            "Knowledge cutoff: 2020-01\n",
            KnowledgeCutoffDate(LocalDate.of(2020, 1, 1)).contribution()
        )
        assertEquals(
            "Knowledge cutoff: 2025-12\n",
            KnowledgeCutoffDate(LocalDate.of(2025, 12, 31)).contribution()
        )
    }
}
