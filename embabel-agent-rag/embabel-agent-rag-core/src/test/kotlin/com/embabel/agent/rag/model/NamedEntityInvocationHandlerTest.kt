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
package com.embabel.agent.rag.model

import com.embabel.agent.rag.service.RetrievableIdentifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NamedEntityInvocationHandlerTest {

    // Test interfaces
    interface Address : NamedEntity {
        val street: String
        val city: String
    }

    interface Person : NamedEntity {
        val age: Int

        @Relationship("HAS_ADDRESS")
        fun getAddresses(): List<Address>

        @Relationship  // Defaults to "HAS_EMPLOYER"
        fun getEmployer(): Company?

        /**
         * Business logic method that invokes relationship methods.
         * This should NOT be proxied as a property/relationship.
         */
        fun addressHistory(): String {
            val addresses = getAddresses()
            return addresses.joinToString(" -> ") { "${it.street}, ${it.city}" }
        }

        /**
         * Another business logic method using a single relationship.
         */
        fun employerName(): String {
            return getEmployer()?.name ?: "Unemployed"
        }
    }

    interface Company : NamedEntity {
        val industry: String
    }

    // Test navigator that returns mock data
    class TestNavigator : RelationshipNavigator {
        val relationships = mutableMapOf<String, MutableMap<String, MutableList<NamedEntityData>>>()

        fun addRelationship(
            entityId: String,
            relationshipName: String,
            direction: RelationshipDirection,
            target: NamedEntityData,
        ) {
            val key = "$entityId:$relationshipName:$direction"
            relationships.getOrPut(key) { mutableMapOf() }
                .getOrPut(relationshipName) { mutableListOf() }
                .add(target)
        }

        override fun findRelated(
            source: RetrievableIdentifier,
            relationshipName: String,
            direction: RelationshipDirection,
        ): List<NamedEntityData> {
            val key = "${source.id}:$relationshipName:$direction"
            return relationships[key]?.get(relationshipName) ?: emptyList()
        }
    }

    private lateinit var navigator: TestNavigator

    @BeforeEach
    fun setUp() {
        navigator = TestNavigator()
    }

    private fun createAddress(id: String, street: String, city: String): NamedEntityData =
        SimpleNamedEntityData(
            id = id,
            name = "$street, $city",
            description = "Address",
            labels = setOf("Address"),
            properties = mapOf("street" to street, "city" to city),
        )

    private fun createCompany(id: String, name: String, industry: String): NamedEntityData =
        SimpleNamedEntityData(
            id = id,
            name = name,
            description = "A company",
            labels = setOf("Company"),
            properties = mapOf("industry" to industry),
        )

    private fun createPerson(id: String, name: String, age: Int): NamedEntityData =
        SimpleNamedEntityData(
            id = id,
            name = name,
            description = "A person",
            labels = setOf("Person"),
            properties = mapOf("age" to age),
        )

    @Nested
    inner class RelationshipNavigationTest {

        @Test
        fun `relationship getter returns list of related entities`() {
            val personData = createPerson("p1", "John", 30)
            val address1 = createAddress("a1", "123 Main St", "Springfield")
            val address2 = createAddress("a2", "456 Oak Ave", "Shelbyville")

            navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address1)
            navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address2)

            val person = personData.toInstance<Person>(navigator, Person::class.java)

            val addresses = person.getAddresses()
            assertEquals(2, addresses.size)
            assertEquals("123 Main St", addresses[0].street)
            assertEquals("456 Oak Ave", addresses[1].street)
        }

        @Test
        fun `nullable relationship returns null when no related entity exists`() {
            val personData = createPerson("p1", "John", 30)
            val person = personData.toInstance<Person>(navigator, Person::class.java)

            // getEmployer() returns Company? (nullable) - should return null without error
            val employer = person.getEmployer()
            assertNull(employer)
        }

        @Test
        fun `collection relationship returns empty list when no related entities exist`() {
            val personData = createPerson("p1", "John", 30)
            val person = personData.toInstance<Person>(navigator, Person::class.java)

            // getAddresses() returns List<Address> - should return empty list without error
            val addresses = person.getAddresses()
            assertNotNull(addresses)
            assertTrue(addresses.isEmpty())
        }

        @Test
        fun `relationship getter returns single entity`() {
            val personData = createPerson("p1", "John", 30)
            val company = createCompany("c1", "Acme Corp", "Technology")

            navigator.addRelationship("p1", "HAS_EMPLOYER", RelationshipDirection.OUTGOING, company)

            val person = personData.toInstance<Person>(navigator, Person::class.java)

            val employer = person.getEmployer()
            assertNotNull(employer)
            assertEquals("Acme Corp", employer?.name)
            assertEquals("Technology", employer?.industry)
        }

        @Test
        fun `default relationship name derived from method name`() {
            val personData = createPerson("p1", "John", 30)
            val company = createCompany("c1", "Acme Corp", "Technology")

            // Note: Using default name "HAS_EMPLOYER" derived from getEmployer()
            navigator.addRelationship("p1", "HAS_EMPLOYER", RelationshipDirection.OUTGOING, company)

            val person = personData.toInstance<Person>(navigator, Person::class.java)

            val employer = person.getEmployer()
            assertNotNull(employer)
            assertEquals("Acme Corp", employer?.name)
        }

        @Test
        fun `relationships are cached after first load`() {
            val personData = createPerson("p1", "John", 30)
            val address1 = createAddress("a1", "123 Main St", "Springfield")

            navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address1)

            val person = personData.toInstance<Person>(navigator, Person::class.java)

            // First call
            val addresses1 = person.getAddresses()
            assertEquals(1, addresses1.size)

            // Add another address to navigator (simulating external change)
            val address2 = createAddress("a2", "456 Oak Ave", "Shelbyville")
            navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address2)

            // Second call should return cached result
            val addresses2 = person.getAddresses()
            assertEquals(1, addresses2.size) // Still 1, cached
        }
    }

    @Nested
    inner class BusinessLogicMethodTest {

        @Test
        fun `business logic method can invoke relationship methods`() {
            val personData = createPerson("p1", "John", 30)
            val address1 = createAddress("a1", "123 Main St", "Springfield")
            val address2 = createAddress("a2", "456 Oak Ave", "Shelbyville")

            navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address1)
            navigator.addRelationship("p1", "HAS_ADDRESS", RelationshipDirection.OUTGOING, address2)

            val person = personData.toInstance<Person>(navigator, Person::class.java)

            // addressHistory() internally calls getAddresses()
            val history = person.addressHistory()
            assertEquals("123 Main St, Springfield -> 456 Oak Ave, Shelbyville", history)
        }

        @Test
        fun `business logic method handles empty relationships`() {
            val personData = createPerson("p1", "John", 30)
            val person = personData.toInstance<Person>(navigator, Person::class.java)

            // addressHistory() with no addresses
            val history = person.addressHistory()
            assertEquals("", history)
        }

        @Test
        fun `business logic method using single relationship`() {
            val personData = createPerson("p1", "John", 30)
            val company = createCompany("c1", "Acme Corp", "Technology")

            navigator.addRelationship("p1", "HAS_EMPLOYER", RelationshipDirection.OUTGOING, company)

            val person = personData.toInstance<Person>(navigator, Person::class.java)

            // employerName() internally calls getEmployer()
            val name = person.employerName()
            assertEquals("Acme Corp", name)
        }

        @Test
        fun `business logic method handles null relationship`() {
            val personData = createPerson("p1", "John", 30)
            val person = personData.toInstance<Person>(navigator, Person::class.java)

            // employerName() with no employer
            val name = person.employerName()
            assertEquals("Unemployed", name)
        }
    }

    @Nested
    inner class PropertyAccessTest {

        @Test
        fun `scalar properties still work with navigator`() {
            val personData = createPerson("p1", "John", 30)
            val person = personData.toInstance<Person>(navigator, Person::class.java)

            assertEquals("p1", person.id)
            assertEquals("John", person.name)
            assertEquals(30, person.age)
        }
    }

    @Nested
    inner class DeriveRelationshipNameTest {

        @Test
        fun `derives name from getter method`() {
            assertEquals("HAS_EMPLOYER", deriveRelationshipName("getEmployer"))
            assertEquals("HAS_PETS", deriveRelationshipName("getPets"))
            assertEquals("HAS_ADDRESSES", deriveRelationshipName("getAddresses"))
        }

        @Test
        fun `handles camelCase to UPPER_SNAKE_CASE`() {
            assertEquals("HAS_DIRECT_REPORTS", deriveRelationshipName("getDirectReports"))
            assertEquals("HAS_HOME_ADDRESS", deriveRelationshipName("getHomeAddress"))
        }

        @Test
        fun `handles is prefix for boolean-style names`() {
            assertEquals("HAS_ACTIVE", deriveRelationshipName("isActive"))
        }
    }

    // Java interface tests are in JavaInterfaceRelationshipTest.java
}
