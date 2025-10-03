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
package com.embabel.agent.core

import com.fasterxml.jackson.annotation.JsonClassDescription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JvmTypeTest {

    class Dog(
        val name: String,
        val breed: String,
    )

    @JsonClassDescription("A feline creature")
    class Cat

    @Test
    fun `should default description`() {
        val type = JvmType(Dog::class.java)
        assertEquals(Dog::class.java.name, type.name)
        assertEquals(Dog::class.java.name, type.description)
    }

    @Test
    fun `should build description from annotation`() {
        val type = JvmType(Cat::class.java)
        assertEquals(Cat::class.java.name, type.name)
        assertEquals("Cat: A feline creature", type.description)
    }

    @Test
    fun `should list properties`() {
        val type = JvmType(Dog::class.java)
        assertEquals(2, type.properties.size)
        assertEquals("name", type.properties[0].name)
        assertEquals("breed", type.properties[1].name)
    }

}
