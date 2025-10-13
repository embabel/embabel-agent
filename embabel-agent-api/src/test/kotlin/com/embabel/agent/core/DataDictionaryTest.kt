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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataDictionaryTest {

    class Person(
        val name: String,
        val age: Int,
    )

    class Address(
        val street: String,
        val city: String,
    )

    class Customer(
        val name: String,
        val address: Address,
    )

    @Test
    fun `should return empty relationships when no domain types have relationships`() {
        val dictionary = DataDictionaryImpl(Person::class.java)
        val relationships = dictionary.allowedRelationships()
        assertEquals(0, relationships.size)
    }

    @Test
    fun `should find relationships in JvmType with nested entity`() {
        val dictionary = DataDictionaryImpl(Customer::class.java, Address::class.java)
        val relationships = dictionary.allowedRelationships()

        assertEquals(1, relationships.size)
        val rel = relationships[0]
        assertEquals("Customer", (rel.from as JvmType).clazz.simpleName)
        assertEquals("Address", (rel.to as JvmType).clazz.simpleName)
        assertEquals("address", rel.relationshipName)
        assertEquals(Cardinality.ONE, rel.cardinality)
    }

    class Company(
        val name: String,
        val headquarters: Address,
        val billingAddress: Address,
    )

    @Test
    fun `should find multiple relationships from same type`() {
        val dictionary = DataDictionaryImpl(Company::class.java, Address::class.java)
        val relationships = dictionary.allowedRelationships()

        assertEquals(2, relationships.size)
        val relationshipNames = relationships.map { it.relationshipName }
        assertTrue(relationshipNames.contains("headquarters"))
        assertTrue(relationshipNames.contains("billingAddress"))
    }

    class Order(
        val customer: Customer,
        val shippingAddress: Address,
    )

    @Test
    fun `should find all relationships across multiple types`() {
        val dictionary = DataDictionaryImpl(Order::class.java, Customer::class.java, Address::class.java)
        val relationships = dictionary.allowedRelationships()

        assertEquals(3, relationships.size)

        val fromOrder = relationships.filter { (it.from as JvmType).clazz.simpleName == "Order" }
        assertEquals(2, fromOrder.size)

        val fromCustomer = relationships.filter { (it.from as JvmType).clazz.simpleName == "Customer" }
        assertEquals(1, fromCustomer.size)
    }

    @Test
    fun `should find relationships in DynamicType`() {
        val addressType = DynamicType(
            name = "Address",
            ownProperties = listOf(
                SimplePropertyDefinition(name = "street", type = "string"),
            ),
        )

        val personType = DynamicType(
            name = "Person",
            ownProperties = listOf(
                SimplePropertyDefinition(name = "name", type = "string"),
                DomainTypePropertyDefinition(name = "address", type = addressType),
            ),
        )

        val dictionary = DataDictionaryImpl(listOf(personType, addressType))
        val relationships = dictionary.allowedRelationships()

        assertEquals(1, relationships.size)
        assertEquals("Person", relationships[0].from.name)
        assertEquals("Address", relationships[0].to.name)
        assertEquals("address", relationships[0].relationshipName)
        assertEquals(Cardinality.ONE, relationships[0].cardinality)
    }

    @Test
    fun `should find relationships between DynamicType and JvmType`() {
        val jvmAddress = JvmType(Address::class.java)

        val personType = DynamicType(
            name = "Person",
            ownProperties = listOf(
                SimplePropertyDefinition(name = "name", type = "string"),
                DomainTypePropertyDefinition(name = "homeAddress", type = jvmAddress),
            ),
        )

        val dictionary = DataDictionaryImpl(listOf(personType, jvmAddress))
        val relationships = dictionary.allowedRelationships()

        assertEquals(1, relationships.size)
        assertEquals("Person", relationships[0].from.name)
        assertEquals(Address::class.java.name, relationships[0].to.name)
        assertEquals("homeAddress", relationships[0].relationshipName)
    }

    @Test
    fun `should include inherited relationships`() {
        val addressType = DynamicType(
            name = "Address",
            ownProperties = listOf(
                SimplePropertyDefinition(name = "street", type = "string"),
            ),
        )

        val basePersonType = DynamicType(
            name = "BasePerson",
            ownProperties = listOf(
                SimplePropertyDefinition(name = "name", type = "string"),
                DomainTypePropertyDefinition(name = "address", type = addressType),
            ),
        )

        val employeeType = DynamicType(
            name = "Employee",
            ownProperties = listOf(
                SimplePropertyDefinition(name = "employeeId", type = "string"),
            ),
            parents = listOf(basePersonType),
        )

        val dictionary = DataDictionaryImpl(listOf(employeeType, basePersonType, addressType))
        val relationships = dictionary.allowedRelationships()

        // Employee should have the inherited address relationship
        val employeeRelationships = relationships.filter { it.from.name == "Employee" }
        assertEquals(1, employeeRelationships.size)
        assertEquals("address", employeeRelationships[0].relationshipName)
        assertEquals("Address", employeeRelationships[0].to.name)

        // BasePerson also has the relationship
        val basePersonRelationships = relationships.filter { it.from.name == "BasePerson" }
        assertEquals(1, basePersonRelationships.size)

        // Total relationships
        assertEquals(2, relationships.size)
    }

    class Library(
        val name: String,
        val books: List<Address>,
    )

    @Test
    fun `should capture cardinality LIST for collection relationships`() {
        val dictionary = DataDictionaryImpl(Library::class.java, Address::class.java)
        val relationships = dictionary.allowedRelationships()

        assertEquals(1, relationships.size)
        val rel = relationships[0]
        assertEquals("Library", (rel.from as JvmType).clazz.simpleName)
        assertEquals("Address", (rel.to as JvmType).clazz.simpleName)
        assertEquals("books", rel.relationshipName)
        assertEquals(Cardinality.LIST, rel.cardinality)
    }

    @Test
    fun `should capture different cardinalities in DynamicType`() {
        val bookType = DynamicType(name = "Book")

        val libraryType = DynamicType(
            name = "Library",
            ownProperties = listOf(
                DomainTypePropertyDefinition(
                    name = "featuredBook",
                    type = bookType,
                    cardinality = Cardinality.ONE,
                ),
                DomainTypePropertyDefinition(
                    name = "optionalBook",
                    type = bookType,
                    cardinality = Cardinality.OPTIONAL,
                ),
                DomainTypePropertyDefinition(
                    name = "books",
                    type = bookType,
                    cardinality = Cardinality.LIST,
                ),
                DomainTypePropertyDefinition(
                    name = "uniqueBooks",
                    type = bookType,
                    cardinality = Cardinality.SET,
                ),
            ),
        )

        val dictionary = DataDictionaryImpl(listOf(libraryType, bookType))
        val relationships = dictionary.allowedRelationships()

        assertEquals(4, relationships.size)

        val featuredRel = relationships.find { it.relationshipName == "featuredBook" }!!
        assertEquals(Cardinality.ONE, featuredRel.cardinality)

        val optionalRel = relationships.find { it.relationshipName == "optionalBook" }!!
        assertEquals(Cardinality.OPTIONAL, optionalRel.cardinality)

        val listRel = relationships.find { it.relationshipName == "books" }!!
        assertEquals(Cardinality.LIST, listRel.cardinality)

        val setRel = relationships.find { it.relationshipName == "uniqueBooks" }!!
        assertEquals(Cardinality.SET, setRel.cardinality)
    }
}
