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
package com.embabel.agent.api.common.support

import com.embabel.agent.core.DomainType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MultiTransformationActionTest {

    @Test
    fun `domainTypes should handle normal case correctly`() {
        val action = MultiTransformationAction(
            name = "testAction",
            inputs = emptySet(),
            inputClasses = listOf(String::class.java, Int::class.java),
            outputClass = String::class.java,
            toolGroups = emptySet()
        ) { _ -> "result" }

        val domainTypes = action.domainTypes
        assertNotNull(domainTypes, "domainTypes should not be null")
        assertEquals(2, domainTypes.size, "Should have 2 domain types")

        val typeNames = domainTypes.map { it.name }.toSet()
        assertTrue(typeNames.contains("java.lang.String"), "Should contain String type")
        assertTrue(typeNames.contains("int"), "Should contain int type")
    }

    @Test
    fun `domainTypes should handle empty input classes`() {
        val action = MultiTransformationAction<String>(
            name = "testAction",
            inputs = emptySet(),
            inputClasses = emptyList(),
            outputClass = String::class.java,
            toolGroups = emptySet()
        ) { _ -> "result" }

        val domainTypes = action.domainTypes
        assertNotNull(domainTypes, "domainTypes should not be null")
        assertEquals(1, domainTypes.size, "Should have 1 domain type")
        assertEquals("java.lang.String", domainTypes.first().name, "Should contain String type")
    }

    @Test
    fun `domainTypes should handle void output class gracefully`() {
        // Create action with Void output type
        val action = MultiTransformationAction<Void>(
            name = "testAction",
            inputs = emptySet(),
            inputClasses = listOf(String::class.java),
            outputClass = Void.TYPE,
            toolGroups = emptySet()
        ) { _ -> null }

        val domainTypes = action.domainTypes
        assertNotNull(domainTypes, "domainTypes should not be null")
        // Should have String input type and potentially void type
        assertTrue(domainTypes.isNotEmpty(), "Should have at least one domain type")
    }

    @Test
    fun `domainTypes should handle primitive types`() {
        val action = MultiTransformationAction<Int>(
            name = "testAction",
            inputs = emptySet(),
            inputClasses = listOf(
                Int::class.javaPrimitiveType ?: Int::class.java,
                Double::class.javaPrimitiveType ?: Double::class.java
            ),
            outputClass = Int::class.javaPrimitiveType ?: Int::class.java,
            toolGroups = emptySet()
        ) { _ -> 42 }

        val domainTypes = action.domainTypes
        assertNotNull(domainTypes, "domainTypes should not be null")
        assertFalse(domainTypes.isEmpty(), "Should have domain types for primitive types")
    }

    @Test
    fun `domainTypes should never return null even with problematic inputs`() {
        // Test with classes that might cause issues
        val action = MultiTransformationAction<Object>(
            name = "testAction",
            inputs = emptySet(),
            inputClasses = listOf(Object::class.java, Array<String>::class.java),
            outputClass = Object::class.java,
            toolGroups = emptySet()
        ) { _ -> Object() }

        val domainTypes = action.domainTypes
        assertNotNull(domainTypes, "domainTypes should never be null")
        // Even if there are issues, it should return an empty collection, not null
        assertTrue(domainTypes is Collection<DomainType>, "Should return a collection")
    }
}
