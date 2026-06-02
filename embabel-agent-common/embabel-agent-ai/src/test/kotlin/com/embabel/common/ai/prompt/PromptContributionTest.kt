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

class PromptContributionTest {

    @Test
    fun `should create with content only`() {
        // Arrange & Act
        val contribution = PromptContribution(content = "Test content")

        // Assert
        assertEquals("Test content", contribution.content)
        assertEquals(PromptContributionLocation.BEGINNING, contribution.location)
        assertNull(contribution.role)
    }

    @Test
    fun `should create with all parameters`() {
        // Arrange & Act
        val contribution = PromptContribution(
            content = "Test content",
            location = PromptContributionLocation.END,
            role = "test-role"
        )

        // Assert
        assertEquals("Test content", contribution.content)
        assertEquals(PromptContributionLocation.END, contribution.location)
        assertEquals("test-role", contribution.role)
    }

    @Test
    fun `should default to BEGINNING location`() {
        // Arrange & Act
        val contribution = PromptContribution(content = "Content")

        // Assert
        assertEquals(PromptContributionLocation.BEGINNING, contribution.location)
    }

    @Test
    fun `should allow null role`() {
        // Arrange & Act
        val contribution = PromptContribution(
            content = "Content",
            role = null
        )

        // Assert
        assertNull(contribution.role)
    }

    @Test
    fun `should support copy with modified content`() {
        // Arrange
        val original = PromptContribution(content = "Original")

        // Act
        val modified = original.copy(content = "Modified")

        // Assert
        assertEquals("Modified", modified.content)
        assertEquals("Original", original.content)
    }

    @Test
    fun `should support copy with modified location`() {
        // Arrange
        val original = PromptContribution(
            content = "Content",
            location = PromptContributionLocation.BEGINNING
        )

        // Act
        val modified = original.copy(location = PromptContributionLocation.END)

        // Assert
        assertEquals(PromptContributionLocation.END, modified.location)
        assertEquals(PromptContributionLocation.BEGINNING, original.location)
    }

    @Test
    fun `should support copy with modified role`() {
        // Arrange
        val original = PromptContribution(
            content = "Content",
            role = "role1"
        )

        // Act
        val modified = original.copy(role = "role2")

        // Assert
        assertEquals("role2", modified.role)
        assertEquals("role1", original.role)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val contribution1 = PromptContribution(
            content = "Test",
            location = PromptContributionLocation.BEGINNING,
            role = "role1"
        )
        val contribution2 = PromptContribution(
            content = "Test",
            location = PromptContributionLocation.BEGINNING,
            role = "role1"
        )
        val contribution3 = PromptContribution(
            content = "Different",
            location = PromptContributionLocation.BEGINNING,
            role = "role1"
        )

        // Assert
        assertEquals(contribution1, contribution2)
        assertNotEquals(contribution1, contribution3)
    }

    @Test
    fun `should have knowledge cutoff role constant`() {
        // Assert
        assertEquals("knowledge_cutoff", PromptContribution.KNOWLEDGE_CUTOFF_ROLE)
    }

    @Test
    fun `should have current date role constant`() {
        // Assert
        assertEquals("current_date", PromptContribution.CURRENT_DATE_ROLE)
    }

    @Test
    fun `should create contribution with knowledge cutoff role`() {
        // Arrange & Act
        val contribution = PromptContribution(
            content = "Knowledge cutoff: 2024-01-01",
            role = PromptContribution.KNOWLEDGE_CUTOFF_ROLE
        )

        // Assert
        assertEquals("knowledge_cutoff", contribution.role)
    }

    @Test
    fun `should create contribution with current date role`() {
        // Arrange & Act
        val contribution = PromptContribution(
            content = "Current date: 2024-05-31",
            role = PromptContribution.CURRENT_DATE_ROLE
        )

        // Assert
        assertEquals("current_date", contribution.role)
    }

    @Test
    fun `should handle empty content`() {
        // Arrange & Act
        val contribution = PromptContribution(content = "")

        // Assert
        assertEquals("", contribution.content)
    }

    @Test
    fun `should handle multiline content`() {
        // Arrange
        val multilineContent = """
            Line 1
            Line 2
            Line 3
        """.trimIndent()

        // Act
        val contribution = PromptContribution(content = multilineContent)

        // Assert
        assertEquals(multilineContent, contribution.content)
        assertTrue(contribution.content.contains("Line 1"))
        assertTrue(contribution.content.contains("Line 2"))
        assertTrue(contribution.content.contains("Line 3"))
    }

    @Test
    fun `should support all location values`() {
        // Arrange & Act
        val beginning = PromptContribution(
            content = "Content",
            location = PromptContributionLocation.BEGINNING
        )
        val end = PromptContribution(
            content = "Content",
            location = PromptContributionLocation.END
        )

        // Assert
        assertEquals(PromptContributionLocation.BEGINNING, beginning.location)
        assertEquals(PromptContributionLocation.END, end.location)
    }
}
