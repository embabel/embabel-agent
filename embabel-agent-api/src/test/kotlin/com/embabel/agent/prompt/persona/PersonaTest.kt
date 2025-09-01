/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.prompt.persona

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PersonaTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class SerializationTests {

        @Test
        fun `should serialize and deserialize Persona with defaults`() {
            val persona = Persona(
                name = "Alice",
                persona = "Friendly and helpful",
                voice = "Calm and clear",
                objective = "Assist users with their queries"
            )

            val serialized = objectMapper.writeValueAsString(persona)
            val deserialized = objectMapper.readValue(serialized, Persona::class.java)
            Assertions.assertEquals(persona, deserialized, "Should be able to serialize and deserialize Persona")
        }

        @Test
        fun `should serialize and deserialize Persona with overrides`() {
            val persona = Persona(
                name = "Alice",
                persona = "Friendly and helpful",
                voice = "Calm and clear",
                objective = "Assist users with their queries",
            )

            val serialized = objectMapper.writeValueAsString(persona)
            val deserialized = objectMapper.readValue(serialized, Persona::class.java)
            Assertions.assertEquals(persona, deserialized, "Should be able to serialize and deserialize Persona")
        }
    }

    @Test
    fun `should create persona using create factory method`() {
        val persona = Persona(
            name = "TestBot",
            persona = "Helpful assistant",
            voice = "Professional",
            objective = "Help users"
        )

        Assertions.assertEquals("TestBot", persona.name)
        Assertions.assertEquals("Helpful assistant", persona.persona)
        Assertions.assertEquals("Professional", persona.voice)
        Assertions.assertEquals("Help users", persona.objective)
    }

    @Test
    fun `should create persona using invoke operator`() {
        val persona = Persona(
            name = "InvokeBot",
            persona = "Friendly helper",
            voice = "Casual",
            objective = "Assist with tasks"
        )

        Assertions.assertEquals("InvokeBot", persona.name)
        Assertions.assertEquals("Friendly helper", persona.persona)
        Assertions.assertEquals("Casual", persona.voice)
        Assertions.assertEquals("Assist with tasks", persona.objective)
    }

    @Test
    fun `should generate correct prompt contribution`() {
        val persona = Persona(
            name = "Alice",
            persona = "Friendly and helpful",
            voice = "Calm and clear",
            objective = "Assist users with their queries"
        )

        val contribution = persona.contribution()
        val expectedContribution = """
            You are Alice.
            Your persona: Friendly and helpful.
            Your objective is Assist users with their queries.
            Your voice: Calm and clear.
        """.trimIndent()

        Assertions.assertEquals(expectedContribution, contribution)
    }

    @Test
    fun `should handle empty strings in fields`() {
        val persona = Persona(
            name = "",
            persona = "",
            voice = "",
            objective = ""
        )

        Assertions.assertEquals("", persona.name)
        Assertions.assertEquals("", persona.persona)
        Assertions.assertEquals("", persona.voice)
        Assertions.assertEquals("", persona.objective)

        val contribution = persona.contribution()
        Assertions.assertTrue(contribution.contains("You are ."))
    }

    @Test
    fun `should handle special characters and newlines`() {
        val persona = Persona(
            name = "Test\nBot",
            persona = "Helpful & friendly!",
            voice = "Calm, clear & professional",
            objective = "Help users @work"
        )

        Assertions.assertEquals("Test\nBot", persona.name)
        Assertions.assertEquals("Helpful & friendly!", persona.persona)
        Assertions.assertEquals("Calm, clear & professional", persona.voice)
        Assertions.assertEquals("Help users @work", persona.objective)
    }

    @Test
    fun `should maintain equality for same content`() {
        val persona1 = Persona(
            name = "Alice",
            persona = "Helpful",
            voice = "Clear",
            objective = "Assist"
        )
        val persona2 = Persona(
            name = "Alice",
            persona = "Helpful",
            voice = "Clear",
            objective = "Assist"
        )

        Assertions.assertEquals(persona1, persona2)
        Assertions.assertEquals(persona1.hashCode(), persona2.hashCode())
    }

    @Test
    fun `should not be equal for different content`() {
        val persona1 = Persona(
            name = "Alice",
            persona = "Helpful",
            voice = "Clear",
            objective = "Assist"
        )
        val persona2 = Persona(
            name = "Bob",
            persona = "Helpful",
            voice = "Clear",
            objective = "Assist"
        )

        Assertions.assertNotEquals(persona1, persona2)
    }

}
