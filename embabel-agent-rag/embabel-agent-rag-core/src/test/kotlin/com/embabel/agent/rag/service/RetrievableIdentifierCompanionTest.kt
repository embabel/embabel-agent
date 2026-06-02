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

import com.embabel.agent.rag.model.Retrievable
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RetrievableIdentifierCompanionTest {

    @Test
    fun `forChunk should create identifier with Chunk type`() {
        // Arrange & Act
        val identifier = RetrievableIdentifier.forChunk("chunk-123")

        // Assert
        assertEquals("chunk-123", identifier.id)
        assertEquals("Chunk", identifier.type)
    }

    @Test
    fun `forChunk should handle empty id`() {
        // Arrange & Act
        val identifier = RetrievableIdentifier.forChunk("")

        // Assert
        assertEquals("", identifier.id)
        assertEquals("Chunk", identifier.type)
    }

    @Test
    fun `forChunk should handle special characters in id`() {
        // Arrange & Act
        val identifier = RetrievableIdentifier.forChunk("chunk-123-@#$")

        // Assert
        assertEquals("chunk-123-@#$", identifier.id)
        assertEquals("Chunk", identifier.type)
    }

    @Test
    fun `forUser should create identifier with User type`() {
        // Arrange & Act
        val identifier = RetrievableIdentifier.forUser("user-456")

        // Assert
        assertEquals("user-456", identifier.id)
        assertEquals("User", identifier.type)
    }

    @Test
    fun `forUser should handle empty id`() {
        // Arrange & Act
        val identifier = RetrievableIdentifier.forUser("")

        // Assert
        assertEquals("", identifier.id)
        assertEquals("User", identifier.type)
    }

    @Test
    fun `forUser should handle UUID format`() {
        // Arrange & Act
        val identifier = RetrievableIdentifier.forUser("123e4567-e89b-12d3-a456-426614174000")

        // Assert
        assertEquals("123e4567-e89b-12d3-a456-426614174000", identifier.id)
        assertEquals("User", identifier.type)
    }

    @Test
    fun `from should create identifier using first label from retrievable`() {
        // Arrange
        val retrievable = mockk<Retrievable>()
        every { retrievable.id } returns "entity-789"
        every { retrievable.labels() } returns setOf("Person", "Employee")

        // Act
        val identifier = RetrievableIdentifier.from(retrievable)

        // Assert
        assertEquals("entity-789", identifier.id)
        assertEquals("Person", identifier.type)
    }

    @Test
    fun `from should use class name when no labels exist`() {
        // Arrange
        val retrievable = mockk<Retrievable>()
        every { retrievable.id } returns "entity-999"
        every { retrievable.labels() } returns emptySet()

        // Act
        val identifier = RetrievableIdentifier.from(retrievable)

        // Assert
        assertEquals("entity-999", identifier.id)
        assertNotNull(identifier.type)
        assertTrue(identifier.type.isNotEmpty())
    }

    @Test
    fun `from should handle retrievable with single label`() {
        // Arrange
        val retrievable = mockk<Retrievable>()
        every { retrievable.id } returns "doc-123"
        every { retrievable.labels() } returns setOf("Document")

        // Act
        val identifier = RetrievableIdentifier.from(retrievable)

        // Assert
        assertEquals("doc-123", identifier.id)
        assertEquals("Document", identifier.type)
    }

    @Test
    fun `from should handle retrievable with multiple labels`() {
        // Arrange
        val retrievable = mockk<Retrievable>()
        every { retrievable.id } returns "item-456"
        every { retrievable.labels() } returns setOf("Product", "Item", "Asset")

        // Act
        val identifier = RetrievableIdentifier.from(retrievable)

        // Assert
        assertEquals("item-456", identifier.id)
        assertTrue(setOf("Product", "Item", "Asset").contains(identifier.type))
    }

    @Test
    fun `from should handle retrievable with empty id`() {
        // Arrange
        val retrievable = mockk<Retrievable>()
        every { retrievable.id } returns ""
        every { retrievable.labels() } returns setOf("Empty")

        // Act
        val identifier = RetrievableIdentifier.from(retrievable)

        // Assert
        assertEquals("", identifier.id)
        assertEquals("Empty", identifier.type)
    }

    @Test
    fun `forChunk should create distinct instances`() {
        // Arrange & Act
        val id1 = RetrievableIdentifier.forChunk("chunk-1")
        val id2 = RetrievableIdentifier.forChunk("chunk-2")

        // Assert
        assertNotEquals(id1, id2)
        assertNotSame(id1, id2)
    }

    @Test
    fun `forUser should create distinct instances`() {
        // Arrange & Act
        val id1 = RetrievableIdentifier.forUser("user-1")
        val id2 = RetrievableIdentifier.forUser("user-2")

        // Assert
        assertNotEquals(id1, id2)
        assertNotSame(id1, id2)
    }

    @Test
    fun `forChunk and forUser should have different types`() {
        // Arrange & Act
        val chunkId = RetrievableIdentifier.forChunk("id-123")
        val userId = RetrievableIdentifier.forUser("id-123")

        // Assert
        assertEquals("id-123", chunkId.id)
        assertEquals("id-123", userId.id)
        assertNotEquals(chunkId.type, userId.type)
        assertNotEquals(chunkId, userId)
    }

    @Test
    fun `from should handle retrievable with complex id`() {
        // Arrange
        val retrievable = mockk<Retrievable>()
        every { retrievable.id } returns "prefix:namespace:identifier:123"
        every { retrievable.labels() } returns setOf("Complex")

        // Act
        val identifier = RetrievableIdentifier.from(retrievable)

        // Assert
        assertEquals("prefix:namespace:identifier:123", identifier.id)
        assertEquals("Complex", identifier.type)
    }

    @Test
    fun `companion methods should return RetrievableIdentifier instances`() {
        // Arrange & Act
        val chunk = RetrievableIdentifier.forChunk("test")
        val user = RetrievableIdentifier.forUser("test")
        val retrievable = mockk<Retrievable>()
        every { retrievable.id } returns "test"
        every { retrievable.labels() } returns setOf("Test")
        val from = RetrievableIdentifier.from(retrievable)

        // Assert
        assertTrue(chunk is RetrievableIdentifier)
        assertTrue(user is RetrievableIdentifier)
        assertTrue(from is RetrievableIdentifier)
    }
}
