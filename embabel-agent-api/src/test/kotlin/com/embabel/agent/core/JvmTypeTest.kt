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
        assertEquals(2, type.ownProperties.size)
        assertEquals("name", type.ownProperties[0].name)
        assertEquals("breed", type.ownProperties[1].name)
    }

    @Test
    fun `should list scalar properties as SimplePropertyDefinition`() {
        val type = JvmType(Dog::class.java)
        val nameProperty = type.ownProperties[0]
        assertEquals("name", nameProperty.name)
        assert(nameProperty is SimplePropertyDefinition)
        assertEquals("String", (nameProperty as SimplePropertyDefinition).type)
    }

    class Owner(
        val name: String,
        val dog: Dog,
    )

    @Test
    fun `should nest entity properties as DomainTypePropertyDefinition`() {
        val type = JvmType(Owner::class.java)
        assertEquals(2, type.ownProperties.size)

        val nameProperty = type.ownProperties[0]
        assertEquals("name", nameProperty.name)
        assert(nameProperty is SimplePropertyDefinition)

        val dogProperty = type.ownProperties[1]
        assertEquals("dog", dogProperty.name)
        assert(dogProperty is DomainTypePropertyDefinition)

        val dogType = (dogProperty as DomainTypePropertyDefinition).type
        assert(dogType is JvmType)
        assertEquals(Dog::class.java.name, (dogType as JvmType).className)
    }

    class Kennel(
        val name: String,
        val capacity: Int,
        val dogs: List<Dog>,
    )

    @Test
    fun `should not nest collection types as entities`() {
        val type = JvmType(Kennel::class.java)
        assertEquals(3, type.ownProperties.size)

        val nameProperty = type.ownProperties[0]
        assertEquals("name", nameProperty.name)
        assert(nameProperty is SimplePropertyDefinition)

        val capacityProperty = type.ownProperties[1]
        assertEquals("capacity", capacityProperty.name)
        assert(capacityProperty is SimplePropertyDefinition)

        val dogsProperty = type.ownProperties[2]
        assertEquals("dogs", dogsProperty.name)
        assert(dogsProperty is SimplePropertyDefinition)
        assertEquals("List", (dogsProperty as SimplePropertyDefinition).type)
    }

    @Test
    fun `should verify nested entity has its own properties`() {
        val type = JvmType(Owner::class.java)
        val dogProperty = type.ownProperties[1] as DomainTypePropertyDefinition
        val dogType = dogProperty.type as JvmType

        assertEquals(2, dogType.ownProperties.size)
        assertEquals("name", dogType.ownProperties[0].name)
        assertEquals("breed", dogType.ownProperties[1].name)
    }

    open class Animal(
        val name: String,
    )

    class Horse(
        name: String,
        val breed: String,
    ) : Animal(name)

    @Test
    fun `should detect parent class`() {
        val type = JvmType(Horse::class.java)
        assertEquals(1, type.parents.size)
        assertEquals(Animal::class.java.name, type.parents[0].className)
    }

    @Test
    fun `should include inherited properties in properties`() {
        val type = JvmType(Horse::class.java)
        assertEquals(1, type.ownProperties.size)
        assertEquals("breed", type.ownProperties[0].name)

        assertEquals(2, type.properties.size)
        val propertyNames = type.properties.map { it.name }
        assert(propertyNames.contains("breed"))
        assert(propertyNames.contains("name"))
    }

    interface Vehicle {
        val wheels: Int
    }

    class Truck(
        override val wheels: Int,
        val capacity: Int,
    ) : Vehicle

    @Test
    fun `should detect interface parent`() {
        val type = JvmType(Truck::class.java)
        assertEquals(1, type.parents.size)
        assertEquals(Vehicle::class.java.name, type.parents[0].className)
    }

    @Test
    fun `should include interface properties in properties`() {
        val type = JvmType(Truck::class.java)
        assertEquals(2, type.ownProperties.size)

        // Note: Interface properties are not exposed as fields via reflection,
        // so they won't be included in the properties list
        assertEquals(2, type.properties.size)
        val propertyNames = type.properties.map { it.name }
        assert(propertyNames.contains("wheels"))
        assert(propertyNames.contains("capacity"))
    }

    open class Pet(
        val name: String,
        val age: Int,
    )

    class Parrot(
        name: String,
        age: Int,
        val color: String,
    ) : Pet(name, age)

    @Test
    fun `should not duplicate inherited properties`() {
        val type = JvmType(Parrot::class.java)
        assertEquals(1, type.ownProperties.size)
        assertEquals("color", type.ownProperties[0].name)

        assertEquals(3, type.properties.size)
        val propertyNames = type.properties.map { it.name }
        assert(propertyNames.contains("name"))
        assert(propertyNames.contains("age"))
        assert(propertyNames.contains("color"))

        // Verify no duplicates
        assertEquals(propertyNames.size, propertyNames.distinct().size)
    }

    open class BaseEntity(
        val id: String,
    )

    open class NamedEntity(
        id: String,
        val name: String,
    ) : BaseEntity(id)

    class Product(
        id: String,
        name: String,
        val price: Double,
    ) : NamedEntity(id, name)

    @Test
    fun `should deduplicate properties across multiple inheritance levels`() {
        val type = JvmType(Product::class.java)
        assertEquals(1, type.ownProperties.size)
        assertEquals("price", type.ownProperties[0].name)

        assertEquals(3, type.properties.size)
        val propertyNames = type.properties.map { it.name }
        assert(propertyNames.contains("id"))
        assert(propertyNames.contains("name"))
        assert(propertyNames.contains("price"))

        // Verify no duplicates
        assertEquals(propertyNames.size, propertyNames.distinct().size)
    }

}
