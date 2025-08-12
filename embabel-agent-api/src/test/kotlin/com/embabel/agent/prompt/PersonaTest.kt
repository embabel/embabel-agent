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
package com.embabel.agent.prompt

import com.embabel.agent.prompt.persona.Persona
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PersonaTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class SerializationTests {

        @Test
        fun `should serialize and deserialize Persona with defaults`() {
            val persona = Persona.create(
                name = "Alice",
                persona = "Friendly and helpful",
                voice = "Calm and clear",
                objective = "Assist users with their queries"
            )

            val serialized = objectMapper.writeValueAsString(persona)
            val deserialized = objectMapper.readValue(serialized, Persona::class.java)
            assertEquals(persona, deserialized, "Should be able to serialize and deserialize Persona")
        }

        @Test
        fun `should serialize and deserialize Persona with overrides`() {
            val persona = Persona.create(
                name = "Alice",
                persona = "Friendly and helpful",
                voice = "Calm and clear",
                objective = "Assist users with their queries",
            )

            val serialized = objectMapper.writeValueAsString(persona)
            val deserialized = objectMapper.readValue(serialized, Persona::class.java)
            assertEquals(persona, deserialized, "Should be able to serialize and deserialize Persona")
        }
    }

}
