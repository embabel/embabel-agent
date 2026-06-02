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
package com.embabel.agent.rag.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FactTest {

    @Test
    fun `should create Fact with required parameters`() {
        // Arrange & Act
        val fact = Fact(
            assertion = "The sky is blue",
            authority = "Science Textbook"
        )

        // Assert
        assertEquals("The sky is blue", fact.assertion)
        assertEquals("Science Textbook", fact.authority)
        assertNull(fact.uri)
        assertTrue(fact.metadata.isEmpty())
        assertNotNull(fact.id)
    }

    @Test
    fun `should create Fact with all parameters`() {
        // Arrange
        val metadata = mapOf("page" to 42, "chapter" to "Colors")

        // Act
        val fact = Fact(
            assertion = "The sky is blue",
            authority = "Science Textbook",
            uri = "https://example.com/facts/1",
            metadata = metadata,
            id = "fact-123"
        )

        // Assert
        assertEquals("The sky is blue", fact.assertion)
        assertEquals("Science Textbook", fact.authority)
        assertEquals("https://example.com/facts/1", fact.uri)
        assertEquals(metadata, fact.metadata)
        assertEquals("fact-123", fact.id)
    }

    @Test
    fun `should generate unique IDs by default`() {
        // Arrange & Act
        val fact1 = Fact(assertion = "Fact 1", authority = "Authority 1")
        val fact2 = Fact(assertion = "Fact 2", authority = "Authority 2")

        // Assert
        assertNotEquals(fact1.id, fact2.id)
    }

    @Test
    fun `should return assertion as embeddable value`() {
        // Arrange
        val fact = Fact(
            assertion = "The Earth orbits the Sun",
            authority = "Astronomy Book"
        )

        // Act
        val embeddableValue = fact.embeddableValue()

        // Assert
        assertEquals("The Earth orbits the Sun", embeddableValue)
    }

    @Test
    fun `should generate info string with correct format`() {
        // Arrange
        val fact = Fact(
            assertion = "Water boils at 100°C",
            authority = "Physics Textbook",
            id = "fact-456"
        )

        // Act
        val infoString = fact.infoString(verbose = false, indent = 0)

        // Assert
        assertTrue(infoString.contains("Fact fact-456"))
        assertTrue(infoString.contains("Physics Textbook"))
        assertTrue(infoString.contains("Water boils at 100°C"))
    }

    @Test
    fun `should support copy with modified assertion`() {
        // Arrange
        val original = Fact(
            assertion = "Original fact",
            authority = "Authority"
        )

        // Act
        val modified = original.copy(assertion = "Modified fact")

        // Assert
        assertEquals("Modified fact", modified.assertion)
        assertEquals("Original fact", original.assertion)
        assertEquals(original.authority, modified.authority)
    }

    @Test
    fun `should support copy with modified authority`() {
        // Arrange
        val original = Fact(
            assertion = "Some fact",
            authority = "Original Authority"
        )

        // Act
        val modified = original.copy(authority = "New Authority")

        // Assert
        assertEquals("New Authority", modified.authority)
        assertEquals("Original Authority", original.authority)
    }

    @Test
    fun `should support copy with modified uri`() {
        // Arrange
        val original = Fact(
            assertion = "Fact",
            authority = "Authority",
            uri = "https://example.com/1"
        )

        // Act
        val modified = original.copy(uri = "https://example.com/2")

        // Assert
        assertEquals("https://example.com/2", modified.uri)
        assertEquals("https://example.com/1", original.uri)
    }

    @Test
    fun `should support copy with modified metadata`() {
        // Arrange
        val original = Fact(
            assertion = "Fact",
            authority = "Authority",
            metadata = mapOf("key1" to "value1")
        )
        val newMetadata = mapOf("key2" to "value2")

        // Act
        val modified = original.copy(metadata = newMetadata)

        // Assert
        assertEquals(newMetadata, modified.metadata)
        assertEquals(mapOf("key1" to "value1"), original.metadata)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val fact1 = Fact(
            assertion = "Test",
            authority = "Auth",
            id = "same-id"
        )
        val fact2 = Fact(
            assertion = "Test",
            authority = "Auth",
            id = "same-id"
        )
        val fact3 = Fact(
            assertion = "Different",
            authority = "Auth",
            id = "different-id"
        )

        // Assert
        assertEquals(fact1, fact2)
        assertNotEquals(fact1, fact3)
    }

    @Test
    fun `should implement Source interface`() {
        // Arrange & Act
        val fact: Source = Fact(
            assertion = "Test fact",
            authority = "Test authority"
        )

        // Assert
        assertTrue(fact is Source)
        assertTrue(fact is Retrievable)
    }

    @Test
    fun `should handle empty metadata map`() {
        // Arrange & Act
        val fact = Fact(
            assertion = "Fact",
            authority = "Authority",
            metadata = emptyMap()
        )

        // Assert
        assertTrue(fact.metadata.isEmpty())
    }

    @Test
    fun `should handle null uri`() {
        // Arrange & Act
        val fact = Fact(
            assertion = "Fact",
            authority = "Authority",
            uri = null
        )

        // Assert
        assertNull(fact.uri)
    }
}
