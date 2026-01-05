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
package com.embabel.agent.rag.service

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Test interfaces
interface Person : NamedEntity {
    val age: Int
}

interface Manager : NamedEntity {
    val directReports: Int
    val department: String
}

interface Employee : NamedEntity {
    val employeeId: String
}

interface Customer : NamedEntity {
    val customerId: String
}

class NamedEntityDataRepositoryProxyTest {

    private val testDictionary = DataDictionary.fromClasses(
        Person::class.java,
        Manager::class.java,
        Employee::class.java,
        Customer::class.java
    )

    private lateinit var repository: InMemoryNamedEntityDataRepository

    @BeforeEach
    fun setup() {
        repository = InMemoryNamedEntityDataRepository(testDictionary)
    }

    @Nested
    inner class FindProxyByIdTest {

        @Test
        fun `findById returns null when entity not found`() {
            val result = repository.findById("nonexistent", Person::class.java)

            assertNull(result)
        }

        @Test
        fun `findById returns proxy for single matching interface`() {
            repository.save(SimpleNamedEntityData(
                id = "person-1",
                name = "Alice",
                description = "A person",
                labels = setOf("Person"),
                properties = mapOf("age" to 30)
            ))

            val result = repository.findById("person-1", Person::class.java)

            assertNotNull(result)
            assertTrue(result is Person)
            val person = result as Person
            assertEquals("person-1", person.id)
            assertEquals("Alice", person.name)
            assertEquals(30, person.age)
        }

        @Test
        fun `findById returns proxy implementing multiple matching interfaces`() {
            repository.save(SimpleNamedEntityData(
                id = "mgr-1",
                name = "Bob",
                description = "A manager",
                labels = setOf("Person", "Manager"),
                properties = mapOf(
                    "age" to 45,
                    "directReports" to 5,
                    "department" to "Engineering"
                )
            ))

            val result = repository.findById(
                "mgr-1",
                Person::class.java, Manager::class.java
            )

            assertNotNull(result)

            // Can be used as Person
            assertTrue(result is Person)
            val person = result as Person
            assertEquals(45, person.age)

            // Can be used as Manager
            assertTrue(result is Manager)
            val manager = result as Manager
            assertEquals(5, manager.directReports)
            assertEquals("Engineering", manager.department)
        }

        @Test
        fun `findById filters to only matching interfaces`() {
            // Entity has Person and Manager labels, but not Employee
            repository.save(SimpleNamedEntityData(
                id = "mgr-2",
                name = "Carol",
                description = "A manager who is not an employee",
                labels = setOf("Person", "Manager"),
                properties = mapOf(
                    "age" to 50,
                    "directReports" to 3,
                    "department" to "HR"
                )
            ))

            // Request Person, Manager, and Employee interfaces
            val result = repository.findById(
                "mgr-2",
                Person::class.java, Manager::class.java, Employee::class.java
            )

            assertNotNull(result)

            // Implements matching interfaces
            assertTrue(result is Person)
            assertTrue(result is Manager)

            // Does NOT implement Employee (no matching label)
            assertFalse(result is Employee)
        }

        @Test
        fun `findById returns null when no interfaces match labels`() {
            repository.save(SimpleNamedEntityData(
                id = "customer-1",
                name = "Dave",
                description = "A customer",
                labels = setOf("Customer"),
                properties = mapOf("customerId" to "C001")
            ))

            // Request Person interface, but entity has Customer label
            val result = repository.findById("customer-1", Person::class.java)

            assertNull(result)
        }

        @Test
        fun `findById handles entity with many labels`() {
            repository.save(SimpleNamedEntityData(
                id = "multi-1",
                name = "Eve",
                description = "Multiple roles",
                labels = setOf("Person", "Manager", "Employee"),
                properties = mapOf(
                    "age" to 35,
                    "directReports" to 2,
                    "department" to "Sales",
                    "employeeId" to "E001"
                )
            ))

            val result = repository.findById(
                "multi-1",
                Person::class.java, Manager::class.java, Employee::class.java, Customer::class.java
            )

            assertNotNull(result)
            assertTrue(result is Person)
            assertTrue(result is Manager)
            assertTrue(result is Employee)
            assertFalse(result is Customer)  // No Customer label

            val person = result as Person
            val manager = result as Manager
            val employee = result as Employee

            assertEquals(35, person.age)
            assertEquals(2, manager.directReports)
            assertEquals("E001", employee.employeeId)
        }

        @Test
        fun `findById includes Entity label from RetrievableEntity`() {
            repository.save(SimpleNamedEntityData(
                id = "entity-1",
                name = "Frank",
                description = "An entity",
                labels = setOf("Person"),  // SimpleNamedEntityData adds "Entity" via super.labels()
                properties = mapOf("age" to 25)
            ))

            val entity = repository.findById("entity-1")

            // Verify Entity label is present
            assertTrue(entity!!.labels().contains("Entity"))
            assertTrue(entity.labels().contains("Person"))
        }

        @Test
        fun `findById proxy has correct toString`() {
            repository.save(SimpleNamedEntityData(
                id = "str-1",
                name = "Grace",
                description = "Testing toString",
                labels = setOf("Person"),
                properties = mapOf("age" to 28)
            ))

            val result = repository.findById("str-1", Person::class.java)

            assertNotNull(result)
            val str = result.toString()
            assertTrue(str.contains("str-1"))
            assertTrue(str.contains("Grace"))
        }

        @Test
        fun `findById proxy equals works with same ID`() {
            repository.save(SimpleNamedEntityData(
                id = "eq-1",
                name = "Henry",
                description = "First",
                labels = setOf("Person"),
                properties = mapOf("age" to 40)
            ))

            val result1 = repository.findById("eq-1", Person::class.java)
            val result2 = repository.findById("eq-1", Person::class.java)

            assertEquals(result1, result2)
            assertEquals(result1.hashCode(), result2.hashCode())
        }

        @Test
        fun `findById proxy properties are accessible`() {
            repository.save(SimpleNamedEntityData(
                id = "props-1",
                uri = "http://example.com/person/1",
                name = "Ivy",
                description = "Testing properties",
                labels = setOf("Person", "Manager"),
                properties = mapOf(
                    "age" to 33,
                    "directReports" to 4,
                    "department" to "Finance"
                ),
                metadata = mapOf("source" to "test")
            ))

            val result = repository.findById(
                "props-1",
                Person::class.java, Manager::class.java
            )

            assertNotNull(result)

            // NamedEntity properties
            assertEquals("props-1", result!!.id)
            assertEquals("Ivy", result.name)
            assertEquals("Testing properties", result.description)
            assertEquals("http://example.com/person/1", result.uri)
            assertEquals(mapOf("source" to "test"), result.metadata)

            // Custom properties via interfaces
            val person = result as Person
            val manager = result as Manager
            assertEquals(33, person.age)
            assertEquals(4, manager.directReports)
            assertEquals("Finance", manager.department)
        }

    }

    @Nested
    inner class FindEntityByIdTest {

        @Test
        fun `findEntityById returns null when entity not found`() {
            val dictRepository = InMemoryNamedEntityDataRepository(
                dataDictionary = DataDictionary.fromClasses(Person::class.java)
            )

            val result = dictRepository.findEntityById("nonexistent")

            assertNull(result)
        }

        @Test
        fun `findEntityById returns instance implementing single matching interface`() {
            val dictRepository = InMemoryNamedEntityDataRepository(
                dataDictionary = DataDictionary.fromClasses(Person::class.java, Manager::class.java)
            )
            dictRepository.save(SimpleNamedEntityData(
                id = "person-1",
                name = "Alice",
                description = "A person",
                labels = setOf("Person"),
                properties = mapOf("age" to 30)
            ))

            val result = dictRepository.findEntityById("person-1")

            assertNotNull(result)
            assertTrue(result is Person)
            assertFalse(result is Manager)
            assertEquals(30, (result as Person).age)
        }

        @Test
        fun `findEntityById returns instance implementing multiple matching interfaces`() {
            val dictRepository = InMemoryNamedEntityDataRepository(
                dataDictionary = DataDictionary.fromClasses(
                    Person::class.java,
                    Manager::class.java,
                    Employee::class.java
                )
            )
            dictRepository.save(SimpleNamedEntityData(
                id = "mgr-1",
                name = "Bob",
                description = "A manager",
                labels = setOf("Person", "Manager"),
                properties = mapOf(
                    "age" to 45,
                    "directReports" to 5,
                    "department" to "Engineering"
                )
            ))

            val result = dictRepository.findEntityById("mgr-1")

            assertNotNull(result)
            assertTrue(result is Person)
            assertTrue(result is Manager)
            assertFalse(result is Employee)

            assertEquals(45, (result as Person).age)
            assertEquals(5, (result as Manager).directReports)
            assertEquals("Engineering", result.department)
        }

        @Test
        fun `findEntityById returns null when no interfaces match entity labels`() {
            val dictRepository = InMemoryNamedEntityDataRepository(
                dataDictionary = DataDictionary.fromClasses(Person::class.java)
            )
            dictRepository.save(SimpleNamedEntityData(
                id = "customer-1",
                name = "Charlie",
                description = "A customer",
                labels = setOf("Customer"),
                properties = mapOf("customerId" to "C001")
            ))

            val result = dictRepository.findEntityById("customer-1")

            assertNull(result)
        }

        @Test
        fun `findEntityById implements all matching interfaces from dictionary`() {
            val dictRepository = InMemoryNamedEntityDataRepository(
                dataDictionary = DataDictionary.fromClasses(
                    Person::class.java,
                    Manager::class.java,
                    Employee::class.java,
                    Customer::class.java
                )
            )
            dictRepository.save(SimpleNamedEntityData(
                id = "multi-1",
                name = "Diana",
                description = "Multiple roles",
                labels = setOf("Person", "Manager", "Employee"),
                properties = mapOf(
                    "age" to 35,
                    "directReports" to 2,
                    "department" to "Sales",
                    "employeeId" to "E001"
                )
            ))

            val result = dictRepository.findEntityById("multi-1")

            assertNotNull(result)
            assertTrue(result is Person)
            assertTrue(result is Manager)
            assertTrue(result is Employee)
            assertFalse(result is Customer)

            assertEquals(35, (result as Person).age)
            assertEquals(2, (result as Manager).directReports)
            assertEquals("E001", (result as Employee).employeeId)
        }
    }
}
