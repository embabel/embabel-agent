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

class RelationshipDataTest {

    @Test
    fun `should create RelationshipData with name and properties`() {
        // Arrange
        val properties = mapOf("type" to "friend", "since" to 2020)

        // Act
        val relationship = RelationshipData(
            name = "KNOWS",
            properties = properties
        )

        // Assert
        assertEquals("KNOWS", relationship.name)
        assertEquals(2, relationship.properties.size)
        assertEquals("friend", relationship.properties["type"])
        assertEquals(2020, relationship.properties["since"])
    }

    @Test
    fun `should create RelationshipData with name only using default empty map`() {
        // Act
        val relationship = RelationshipData(name = "FOLLOWS")

        // Assert
        assertEquals("FOLLOWS", relationship.name)
        assertTrue(relationship.properties.isEmpty())
    }

    @Test
    fun `should support copy with modified name`() {
        // Arrange
        val original = RelationshipData("LIKES", mapOf("intensity" to "high"))

        // Act
        val modified = original.copy(name = "LOVES")

        // Assert
        assertEquals("LOVES", modified.name)
        assertEquals("LIKES", original.name)
        assertEquals(mapOf("intensity" to "high"), modified.properties)
    }

    @Test
    fun `should support copy with modified properties`() {
        // Arrange
        val original = RelationshipData("WORKS_WITH", emptyMap())

        // Act
        val modified = original.copy(properties = mapOf("role" to "manager", "department" to "engineering"))

        // Assert
        assertEquals("WORKS_WITH", modified.name)
        assertEquals(2, modified.properties.size)
        assertTrue(original.properties.isEmpty())
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val properties = mapOf("weight" to 0.8)
        val relationship1 = RelationshipData("RELATED_TO", properties)
        val relationship2 = RelationshipData("RELATED_TO", properties)
        val relationship3 = RelationshipData("DIFFERENT", properties)

        // Assert
        assertEquals(relationship1, relationship2)
        assertNotEquals(relationship1, relationship3)
    }

    @Test
    fun `should support component functions`() {
        // Arrange
        val properties = mapOf("distance" to 5)
        val relationship = RelationshipData("CONNECTED_TO", properties)

        // Act
        val (name, props) = relationship

        // Assert
        assertEquals("CONNECTED_TO", name)
        assertEquals(properties, props)
    }

    @Test
    fun `should handle empty properties map`() {
        // Act
        val relationship = RelationshipData("EMPTY_REL", emptyMap())

        // Assert
        assertEquals("EMPTY_REL", relationship.name)
        assertTrue(relationship.properties.isEmpty())
    }

    @Test
    fun `should handle properties with mixed types`() {
        // Arrange
        val properties: Map<String, Any> = mapOf(
            "string" to "value",
            "number" to 42,
            "boolean" to true,
            "list" to listOf(1, 2, 3)
        )

        // Act
        val relationship = RelationshipData("COMPLEX", properties)

        // Assert
        assertEquals(4, relationship.properties.size)
        assertEquals("value", relationship.properties["string"])
        assertEquals(42, relationship.properties["number"])
        assertEquals(true, relationship.properties["boolean"])
        assertEquals(listOf(1, 2, 3), relationship.properties["list"])
    }

    @Test
    fun `hashCode should be consistent for equal objects`() {
        // Arrange
        val props = mapOf("key" to "value")
        val relationship1 = RelationshipData("TEST", props)
        val relationship2 = RelationshipData("TEST", props)

        // Act & Assert
        assertEquals(relationship1.hashCode(), relationship2.hashCode())
    }

    @Test
    fun `toString should contain field values`() {
        // Arrange
        val relationship = RelationshipData("HAS_PROPERTY", mapOf("count" to 5))

        // Act
        val string = relationship.toString()

        // Assert
        assertTrue(string.contains("HAS_PROPERTY"))
        assertTrue(string.contains("count"))
    }
}
