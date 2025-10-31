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
package com.embabel.agent.spi.support.springai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilteringJacksonOutputConverterTest {

    private val objectMapper = jacksonObjectMapper()

    data class Person(
        val name: String,
        val age: Int,
        val email: String,
        val address: String
    )

    @Test
    fun `test schema should include only specified properties`() {
        val converter = FilteringJacksonOutputConverter<Person>(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = { it == "name" || it == "age" }
        )

        val schema = converter.jsonSchema

        assertTrue(schema.contains("name"))
        assertTrue(schema.contains("age"))
        assertFalse(schema.contains("email"))
        assertFalse(schema.contains("address"))
    }

    @Test
    fun `test schema should exclude specified properties`() {
        val converter = FilteringJacksonOutputConverter<Person>(
            clazz = Person::class.java,
            objectMapper = objectMapper,
            propertyFilter = { it != "email" && it != "address" }
        )

        val schema = converter.jsonSchema

        assertTrue(schema.contains("name"))
        assertTrue(schema.contains("age"))
        assertFalse(schema.contains("email"))
        assertFalse(schema.contains("address"))
    }

}
