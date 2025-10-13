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
        fun `test DynamicType with simple properties can be serialized and deserialized`() {
            val dynamicType = DynamicType(
                name = "Person",
                description = "A person",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "firstName", type = "string", description = "First name"),
                    SimplePropertyDefinition(name = "age", type = "int", description = "Age"),
                ),
            )
            assertEquals(2, dynamicType.ownProperties.size, "Should have 2 properties of its own")
            assertEquals(2, dynamicType.properties.size, "Should not have inherited properties")

            val json = om.writeValueAsString(dynamicType)
            val deserialized = om.readValue<DomainType>(json)
            assertEquals("Person", deserialized.name)
            assertEquals(2, deserialized.ownProperties.size, "Deserialized should have 2 properties of its own")
            assertEquals(2, deserialized.properties.size, "Deserialized should not have inherited properties")
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

        @Test
        fun `test DynamicType with parent includes inherited properties`() {
            val parentType = DynamicType(
                name = "Animal",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "name", type = "string"),
                    SimplePropertyDefinition(name = "age", type = "int"),
                ),
            )

            val childType = DynamicType(
                name = "Dog",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "breed", type = "string"),
                ),
                parents = listOf(parentType),
            )

            assertEquals(1, childType.ownProperties.size)
            assertEquals("breed", childType.ownProperties[0].name)

            assertEquals(3, childType.properties.size)
            val propertyNames = childType.properties.map { it.name }
            assertTrue(propertyNames.contains("breed"))
            assertTrue(propertyNames.contains("name"))
            assertTrue(propertyNames.contains("age"))
        }

        @Test
        fun `test DynamicType with multiple parents includes all inherited properties`() {
            val namedType = DynamicType(
                name = "Named",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "name", type = "string"),
                ),
            )

            val agedType = DynamicType(
                name = "Aged",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "age", type = "int"),
                ),
            )

            val personType = DynamicType(
                name = "Person",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "email", type = "string"),
                ),
                parents = listOf(namedType, agedType),
            )

            assertEquals(1, personType.ownProperties.size)
            assertEquals(3, personType.properties.size)
            val propertyNames = personType.properties.map { it.name }
            assertTrue(propertyNames.contains("email"))
            assertTrue(propertyNames.contains("name"))
            assertTrue(propertyNames.contains("age"))
        }

        @Test
        fun `test DynamicType with nested inheritance includes all properties`() {
            val grandparentType = DynamicType(
                name = "LivingThing",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "alive", type = "boolean"),
                ),
            )

            val parentType = DynamicType(
                name = "Animal",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "name", type = "string"),
                ),
                parents = listOf(grandparentType),
            )

            val childType = DynamicType(
                name = "Dog",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "breed", type = "string"),
                ),
                parents = listOf(parentType),
            )

            assertEquals(1, childType.ownProperties.size)
            assertEquals(3, childType.properties.size)
            val propertyNames = childType.properties.map { it.name }
            assertTrue(propertyNames.contains("breed"))
            assertTrue(propertyNames.contains("name"))
            assertTrue(propertyNames.contains("alive"))
        }

        @Test
        fun `test DynamicType with JvmType parent includes inherited properties`() {
            val jvmParent = JvmType(JvmTypeTest.Dog::class.java)

            val childType = DynamicType(
                name = "ServiceDog",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "serviceType", type = "string"),
                ),
                parents = listOf(jvmParent),
            )

            assertEquals(1, childType.ownProperties.size)
            assertEquals(3, childType.properties.size)
            val propertyNames = childType.properties.map { it.name }
            assertTrue(propertyNames.contains("serviceType"))
            assertTrue(propertyNames.contains("name"))
            assertTrue(propertyNames.contains("breed"))
        }

        @Test
        fun `test DynamicType does not duplicate properties when child overrides parent property`() {
            val parentType = DynamicType(
                name = "Animal",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "name", type = "string"),
                    SimplePropertyDefinition(name = "age", type = "int"),
                ),
            )

            val childType = DynamicType(
                name = "Dog",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "name", type = "string", description = "Dog's name"),
                    SimplePropertyDefinition(name = "breed", type = "string"),
                ),
                parents = listOf(parentType),
            )

            assertEquals(2, childType.ownProperties.size)
            assertEquals(3, childType.properties.size)

            val propertyNames = childType.properties.map { it.name }
            assertTrue(propertyNames.contains("name"))
            assertTrue(propertyNames.contains("age"))
            assertTrue(propertyNames.contains("breed"))

            // Verify no duplicates
            assertEquals(propertyNames.size, propertyNames.distinct().size)

            // Verify child's definition takes precedence
            val nameProperty = childType.properties.find { it.name == "name" }
            assertEquals("Dog's name", nameProperty?.description)
        }

        @Test
        fun `test DynamicType with multiple parents deduplicates properties`() {
            val namedType = DynamicType(
                name = "Named",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "name", type = "string"),
                ),
            )

            val identifiedType = DynamicType(
                name = "Identified",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "id", type = "string"),
                    SimplePropertyDefinition(name = "name", type = "string"),
                ),
            )

            val personType = DynamicType(
                name = "Person",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "email", type = "string"),
                ),
                parents = listOf(namedType, identifiedType),
            )

            assertEquals(1, personType.ownProperties.size)
            assertEquals(3, personType.properties.size)

            val propertyNames = personType.properties.map { it.name }
            assertTrue(propertyNames.contains("email"))
            assertTrue(propertyNames.contains("name"))
            assertTrue(propertyNames.contains("id"))

            // Verify no duplicates (name appears in both parents)
            assertEquals(propertyNames.size, propertyNames.distinct().size)
        }

        @Test
        fun `test DynamicType deduplicates properties across deep inheritance hierarchy`() {
            val livingType = DynamicType(
                name = "Living",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "alive", type = "boolean"),
                    SimplePropertyDefinition(name = "name", type = "string"),
                ),
            )

            val animalType = DynamicType(
                name = "Animal",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "name", type = "string"),
                    SimplePropertyDefinition(name = "species", type = "string"),
                ),
                parents = listOf(livingType),
            )

            val dogType = DynamicType(
                name = "Dog",
                ownProperties = listOf(
                    SimplePropertyDefinition(name = "breed", type = "string"),
                ),
                parents = listOf(animalType),
            )

            assertEquals(1, dogType.ownProperties.size)
            assertEquals(4, dogType.properties.size)

            val propertyNames = dogType.properties.map { it.name }
            assertTrue(propertyNames.contains("breed"))
            assertTrue(propertyNames.contains("name"))
            assertTrue(propertyNames.contains("species"))
            assertTrue(propertyNames.contains("alive"))

            // Verify no duplicates (name appears at multiple levels)
            assertEquals(propertyNames.size, propertyNames.distinct().size)
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
    inner class MixedDomainTypeSerialization {

        @Test
        fun `test list of mixed DomainTypes can be serialized and deserialized`() {
            val types: List<DomainType> = listOf(
                DynamicType(name = "DynamicOne"),
                JvmType(String::class.java),
                DynamicType(
                    name = "DynamicTwo",
                    ownProperties = listOf(SimplePropertyDefinition("field", "string"))
                ),
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
    inner class PropertyDefinitionSerialization {

        @Test
        fun `test PropertyDefinition can be serialized and deserialized`() {
            val property = SimplePropertyDefinition(
                name = "testField",
                type = "string",
                description = "A test field",
            )
            val json = om.writeValueAsString(property)
            val deserialized = om.readValue<PropertyDefinition>(json)
            assertEquals("testField", deserialized.name)
            assertEquals("string", (deserialized as SimplePropertyDefinition).type)
            assertEquals("A test field", deserialized.description)
        }
    }
}
