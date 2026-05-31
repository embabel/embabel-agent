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
package com.embabel.agent.domain.library

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PersonTest {

    @Test
    fun `should create person with name`() {
        // Arrange & Act
        val person = PersonImpl("John Doe")

        // Assert
        assertEquals("John Doe", person.name)
    }

    @Test
    fun `should implement Person interface`() {
        // Arrange
        val person: Person = PersonImpl("Jane Smith")

        // Assert
        assertTrue(person is Person)
        assertEquals("Jane Smith", person.name)
    }

    @Test
    fun `should support data class equality`() {
        // Arrange
        val person1 = PersonImpl("Alice")
        val person2 = PersonImpl("Alice")
        val person3 = PersonImpl("Bob")

        // Assert
        assertEquals(person1, person2)
        assertNotEquals(person1, person3)
    }

    @Test
    fun `should deserialize to PersonImpl from JSON`() {
        // Arrange
        val json = """{"name":"Test Person"}"""
        val mapper = ObjectMapper().registerKotlinModule()

        // Act
        val person: Person = mapper.readValue(json)

        // Assert
        assertNotNull(person)
        assertTrue(person is PersonImpl)
        assertEquals("Test Person", person.name)
    }

    @Test
    fun `should serialize PersonImpl to JSON`() {
        // Arrange
        val person = PersonImpl("Serialized Person")
        val mapper = ObjectMapper().registerKotlinModule()

        // Act
        val json = mapper.writeValueAsString(person)

        // Assert
        assertTrue(json.contains("Serialized Person"))
        assertTrue(json.contains("name"))
    }
}
