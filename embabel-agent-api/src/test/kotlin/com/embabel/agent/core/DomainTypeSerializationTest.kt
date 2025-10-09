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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomainTypeSerializationTest {

    private val om = jacksonObjectMapper()

    @Nested
    inner class DynamicTypeSerialization {

        @Test
        fun `test simple DynamicType can be serialized and deserialized`() {
            val dynamicType = DynamicType(
                name = "TestType",
                description = "A test type",
            )
            val json = om.writeValueAsString(dynamicType)
            assertTrue(json.contains("TestType"))
            assertTrue(json.contains("A test type"))
            val deserialized = om.readValue<DynamicType>(json)
            assertEquals("TestType", deserialized.name)
            assertEquals("A test type", deserialized.description)
            assertEquals(emptyList(), deserialized.properties)
        }

        @Test
        fun `test DynamicType with properties can be serialized and deserialized`() {
            val dynamicType = DynamicType(
                name = "Person",
                description = "A person",
                properties = listOf(
                    PropertyDefinition(name = "firstName", type = "string", description = "First name"),
                    PropertyDefinition(name = "age", type = "int", description = "Age"),
                ),
            )
            val json = om.writeValueAsString(dynamicType)
            val deserialized = om.readValue<DynamicType>(json)
            assertEquals("Person", deserialized.name)
            assertEquals(2, deserialized.properties.size)
            assertEquals("firstName", deserialized.properties[0].name)
            assertEquals("age", deserialized.properties[1].name)
        }

        @Test
        fun `test DynamicType polymorphic serialization`() {
            val dynamicType: DomainType = DynamicType(name = "TestType")
            val json = om.writeValueAsString(dynamicType)
            val deserialized = om.readValue<DomainType>(json)
            assertTrue(deserialized is DynamicType)
            assertEquals("TestType", deserialized.name)
        }
    }

    @Nested
    inner class JvmTypeSerialization {

        @Test
        fun `test JvmType can be serialized and deserialized`() {
            val jvmType = JvmType(String::class.java)
            val json = om.writeValueAsString(jvmType)
            assertTrue(json.contains("java.lang.String"))
            val deserialized = om.readValue<JvmType>(json)
            assertEquals("java.lang.String", deserialized.className)
            assertEquals(String::class.java, deserialized.clazz)
        }

        @Test
        fun `test JvmType polymorphic serialization`() {
            val jvmType: DomainType = JvmType(Integer::class.java)
            val json = om.writeValueAsString(jvmType)
            val deserialized = om.readValue<DomainType>(json)
            assertTrue(deserialized is JvmType)
            assertEquals("java.lang.Integer", deserialized.name)
        }
    }

    @Nested
    inner class `Mixed DomainType serialization` {

        @Test
        fun `test list of mixed DomainTypes can be serialized and deserialized`() {
            val types: List<DomainType> = listOf(
                DynamicType(name = "DynamicOne"),
                JvmType(String::class.java),
                DynamicType(name = "DynamicTwo", properties = listOf(PropertyDefinition("field", "string"))),
                JvmType(Integer::class.java),
            )
            val json = om.writeValueAsString(types)
            val deserialized = om.readValue<List<DomainType>>(json)
            assertEquals(4, deserialized.size)
            assertTrue(deserialized[0] is DynamicType)
            assertTrue(deserialized[1] is JvmType)
            assertTrue(deserialized[2] is DynamicType)
            assertTrue(deserialized[3] is JvmType)
            assertEquals("DynamicOne", deserialized[0].name)
            assertEquals("java.lang.String", deserialized[1].name)
        }
    }

    @Nested
    inner class `PropertyDefinition serialization` {

        @Test
        fun `test PropertyDefinition can be serialized and deserialized`() {
            val property = PropertyDefinition(
                name = "testField",
                type = "string",
                description = "A test field",
            )
            val json = om.writeValueAsString(property)
            val deserialized = om.readValue<PropertyDefinition>(json)
            assertEquals("testField", deserialized.name)
            assertEquals("string", deserialized.type)
            assertEquals("A test field", deserialized.description)
        }
    }
}
