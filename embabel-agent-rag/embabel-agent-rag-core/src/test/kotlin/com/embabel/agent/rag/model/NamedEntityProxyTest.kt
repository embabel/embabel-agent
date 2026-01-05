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
package com.embabel.agent.rag.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Employee interface for testing multi-interface proxy.
 */
interface Employee : NamedEntity {
    val department: String
    val employeeId: String
}

/**
 * Manager interface for testing multi-interface proxy.
 */
interface Manager : NamedEntity {
    val directReports: Int
    val level: String
}

/**
 * Interface with boolean property for testing isXxx() pattern.
 */
interface ActiveEntity : NamedEntity {
    val isActive: Boolean
}

class NamedEntityProxyTest {

    @Test
    fun `proxy implements single interface`() {
        val entityData = SimpleNamedEntityData(
            id = "emp-1",
            name = "Alice",
            description = "Software Engineer",
            labels = setOf("Employee"),
            properties = mapOf(
                "department" to "Engineering",
                "employeeId" to "E001"
            )
        )

        val employee: Employee = entityData.toInstance(Employee::class.java)

        assertEquals("emp-1", employee.id)
        assertEquals("Alice", employee.name)
        assertEquals("Software Engineer", employee.description)
        assertEquals("Engineering", employee.department)
        assertEquals("E001", employee.employeeId)
    }

    @Test
    fun `proxy implements multiple interfaces`() {
        val entityData = SimpleNamedEntityData(
            id = "mgr-1",
            name = "Bob",
            description = "Engineering Manager",
            labels = setOf("Employee", "Manager"),
            properties = mapOf(
                "department" to "Engineering",
                "employeeId" to "E002",
                "directReports" to 5,
                "level" to "Senior"
            )
        )

        val proxy = entityData.toInstance<NamedEntity>(Employee::class.java, Manager::class.java)

        // Can be used as Employee
        val employee = proxy as Employee
        assertEquals("Engineering", employee.department)
        assertEquals("E002", employee.employeeId)

        // Can be used as Manager
        val manager = proxy as Manager
        assertEquals(5, manager.directReports)
        assertEquals("Senior", manager.level)

        // Shared NamedEntity properties work through both
        assertEquals("mgr-1", employee.id)
        assertEquals("mgr-1", manager.id)
        assertEquals("Bob", employee.name)
        assertEquals("Bob", manager.name)
    }

    @Test
    fun `proxy instanceof checks work for all interfaces`() {
        val entityData = SimpleNamedEntityData(
            id = "multi-1",
            name = "Carol",
            description = "Multi-role",
            labels = setOf("Employee", "Manager"),
            properties = mapOf(
                "department" to "HR",
                "employeeId" to "E003",
                "directReports" to 3,
                "level" to "Mid"
            )
        )

        val proxy = entityData.toInstance<NamedEntity>(Employee::class.java, Manager::class.java)

        assertTrue(proxy is Employee)
        assertTrue(proxy is Manager)
        assertTrue(proxy is NamedEntity)
    }

    @Test
    fun `proxy handles NamedEntity methods`() {
        val entityData = SimpleNamedEntityData(
            id = "ne-1",
            uri = "http://example.com/entity/1",
            name = "Test",
            description = "Test entity",
            labels = setOf("Employee"),
            properties = mapOf("department" to "Test"),
            metadata = mapOf("source" to "test")
        )

        val proxy: Employee = entityData.toInstance(Employee::class.java)

        assertEquals("ne-1", proxy.id)
        assertEquals("Test", proxy.name)
        assertEquals("Test entity", proxy.description)
        assertEquals("http://example.com/entity/1", proxy.uri)
        assertEquals(mapOf("source" to "test"), proxy.metadata)
        assertTrue(proxy.labels().contains("Employee"))
        assertTrue(proxy.labels().contains("Entity"))
    }

    @Test
    fun `proxy toString is readable`() {
        val entityData = SimpleNamedEntityData(
            id = "str-1",
            name = "ToString Test",
            description = "Testing toString",
            labels = setOf("Employee"),
            properties = mapOf("department" to "QA")
        )

        val proxy: Employee = entityData.toInstance(Employee::class.java)
        val str = proxy.toString()

        assertTrue(str.contains("str-1"))
        assertTrue(str.contains("ToString Test"))
    }

    @Test
    fun `proxy equals works correctly`() {
        val entityData1 = SimpleNamedEntityData(
            id = "eq-1",
            name = "Entity 1",
            description = "First",
            labels = setOf("Employee"),
            properties = mapOf("department" to "A")
        )

        val entityData2 = SimpleNamedEntityData(
            id = "eq-1",  // Same ID
            name = "Entity 1 Copy",
            description = "Copy",
            labels = setOf("Employee"),
            properties = mapOf("department" to "B")
        )

        val entityData3 = SimpleNamedEntityData(
            id = "eq-2",  // Different ID
            name = "Entity 2",
            description = "Second",
            labels = setOf("Employee"),
            properties = mapOf("department" to "C")
        )

        val proxy1: Employee = entityData1.toInstance(Employee::class.java)
        val proxy2: Employee = entityData2.toInstance(Employee::class.java)
        val proxy3: Employee = entityData3.toInstance(Employee::class.java)

        // Same ID means equal
        assertEquals(proxy1, proxy2)
        assertEquals(proxy1.hashCode(), proxy2.hashCode())

        // Different ID means not equal
        assertNotEquals(proxy1, proxy3)
    }

    @Test
    fun `proxy handles boolean isXxx properties`() {
        val entityData = SimpleNamedEntityData(
            id = "bool-1",
            name = "Boolean Test",
            description = "Testing boolean",
            labels = setOf("ActiveEntity"),
            properties = mapOf("isActive" to true)
        )

        val proxy: ActiveEntity = entityData.toInstance(ActiveEntity::class.java)

        assertTrue(proxy.isActive)
    }

    @Test
    fun `proxy handles null properties gracefully`() {
        val entityData = SimpleNamedEntityData(
            id = "null-1",
            name = "Null Test",
            description = "Testing nulls",
            labels = setOf("Employee"),
            properties = emptyMap()  // No department or employeeId
        )

        val proxy: Employee = entityData.toInstance(Employee::class.java)

        assertNull(proxy.department)
        assertNull(proxy.employeeId)
    }

    @Test
    fun `proxy embeddableValue delegates to entityData`() {
        val entityData = SimpleNamedEntityData(
            id = "embed-1",
            name = "Embed Test",
            description = "Testing embeddable",
            labels = setOf("Employee"),
            properties = mapOf("department" to "Dev")
        )

        val proxy: Employee = entityData.toInstance(Employee::class.java)
        val embeddable = proxy.embeddableValue()

        // Should contain the property
        assertTrue(embeddable.contains("department=Dev"))
    }
}
