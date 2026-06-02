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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelSelectionCriteriaTest {

    @Test
    fun `should create ByRoleModelSelectionCriteria`() {
        // Arrange & Act
        val criteria = ModelSelectionCriteria.byRole("assistant")

        // Assert
        assertTrue(criteria is ByRoleModelSelectionCriteria)
        assertEquals("assistant", (criteria as ByRoleModelSelectionCriteria).role)
    }

    @Test
    fun `should create ByNameModelSelectionCriteria`() {
        // Arrange & Act
        val criteria = ModelSelectionCriteria.byName("gpt-4")

        // Assert
        assertTrue(criteria is ByNameModelSelectionCriteria)
        assertEquals("gpt-4", (criteria as ByNameModelSelectionCriteria).name)
    }

    @Test
    fun `should create RandomByNameModelSelectionCriteria`() {
        // Arrange & Act
        val criteria = ModelSelectionCriteria.randomOf("model1", "model2", "model3")

        // Assert
        assertTrue(criteria is RandomByNameModelSelectionCriteria)
        assertEquals(listOf("model1", "model2", "model3"), (criteria as RandomByNameModelSelectionCriteria).names)
    }

    @Test
    fun `should create FallbackByNameModelSelectionCriteria`() {
        // Arrange & Act
        val criteria = ModelSelectionCriteria.firstOf("primary", "fallback1", "fallback2")

        // Assert
        assertTrue(criteria is FallbackByNameModelSelectionCriteria)
        assertEquals(listOf("primary", "fallback1", "fallback2"), (criteria as FallbackByNameModelSelectionCriteria).names)
    }

    @Test
    fun `should provide Auto criteria`() {
        // Act
        val criteria = ModelSelectionCriteria.Auto

        // Assert
        assertSame(AutoModelSelectionCriteria, criteria)
        assertEquals("AutoModelSelectionCriteria", criteria.toString())
    }

    @Test
    fun `should provide PlatformDefault criteria`() {
        // Act
        val criteria = ModelSelectionCriteria.PlatformDefault

        // Assert
        assertSame(DefaultModelSelectionCriteria, criteria)
        assertEquals("DefaultModelSelectionCriteria", criteria.toString())
    }

    @Test
    fun `should create PreResolvedModelSelectionCriteria`() {
        // Arrange
        val resolvedService = "MyResolvedService"

        // Act
        val criteria = ModelSelectionCriteria.preResolved(resolvedService)

        // Assert
        assertTrue(criteria is PreResolvedModelSelectionCriteria<*>)
        assertEquals(resolvedService, (criteria as PreResolvedModelSelectionCriteria<*>).resolved)
        assertTrue(criteria.toString().contains("PreResolvedModelSelectionCriteria"))
        assertTrue(criteria.toString().contains("String"))
    }

    @Test
    fun `ByRoleModelSelectionCriteria should support equality`() {
        // Arrange
        val criteria1 = ByRoleModelSelectionCriteria("assistant")
        val criteria2 = ByRoleModelSelectionCriteria("assistant")
        val criteria3 = ByRoleModelSelectionCriteria("system")

        // Assert
        assertEquals(criteria1, criteria2)
        assertNotEquals(criteria1, criteria3)
    }

    @Test
    fun `ByNameModelSelectionCriteria should support equality`() {
        // Arrange
        val criteria1 = ByNameModelSelectionCriteria("gpt-4")
        val criteria2 = ByNameModelSelectionCriteria("gpt-4")
        val criteria3 = ByNameModelSelectionCriteria("gpt-3.5")

        // Assert
        assertEquals(criteria1, criteria2)
        assertNotEquals(criteria1, criteria3)
    }

    @Test
    fun `RandomByNameModelSelectionCriteria should support equality`() {
        // Arrange
        val criteria1 = RandomByNameModelSelectionCriteria(listOf("model1", "model2"))
        val criteria2 = RandomByNameModelSelectionCriteria(listOf("model1", "model2"))
        val criteria3 = RandomByNameModelSelectionCriteria(listOf("model1", "model3"))

        // Assert
        assertEquals(criteria1, criteria2)
        assertNotEquals(criteria1, criteria3)
    }

    @Test
    fun `FallbackByNameModelSelectionCriteria should support equality`() {
        // Arrange
        val criteria1 = FallbackByNameModelSelectionCriteria(listOf("primary", "fallback"))
        val criteria2 = FallbackByNameModelSelectionCriteria(listOf("primary", "fallback"))
        val criteria3 = FallbackByNameModelSelectionCriteria(listOf("primary", "other"))

        // Assert
        assertEquals(criteria1, criteria2)
        assertNotEquals(criteria1, criteria3)
    }

    @Test
    fun `PreResolvedModelSelectionCriteria should support equality`() {
        // Arrange
        val service = "TestService"
        val criteria1 = PreResolvedModelSelectionCriteria(service)
        val criteria2 = PreResolvedModelSelectionCriteria(service)
        val criteria3 = PreResolvedModelSelectionCriteria("OtherService")

        // Assert
        assertEquals(criteria1, criteria2)
        assertNotEquals(criteria1, criteria3)
    }

    @Test
    fun `AutoModelSelectionCriteria should be singleton`() {
        // Act
        val criteria1 = ModelSelectionCriteria.Auto
        val criteria2 = ModelSelectionCriteria.Auto

        // Assert
        assertSame(criteria1, criteria2)
    }

    @Test
    fun `DefaultModelSelectionCriteria should be singleton`() {
        // Act
        val criteria1 = ModelSelectionCriteria.PlatformDefault
        val criteria2 = ModelSelectionCriteria.PlatformDefault

        // Assert
        assertSame(criteria1, criteria2)
    }

    @Test
    fun `should implement ModelSelectionCriteria interface`() {
        // Arrange
        val criteria1: ModelSelectionCriteria = ByRoleModelSelectionCriteria("test")
        val criteria2: ModelSelectionCriteria = ByNameModelSelectionCriteria("test")
        val criteria3: ModelSelectionCriteria = RandomByNameModelSelectionCriteria(listOf("test"))
        val criteria4: ModelSelectionCriteria = FallbackByNameModelSelectionCriteria(listOf("test"))
        val criteria5: ModelSelectionCriteria = AutoModelSelectionCriteria
        val criteria6: ModelSelectionCriteria = DefaultModelSelectionCriteria
        val criteria7: ModelSelectionCriteria = PreResolvedModelSelectionCriteria("test")

        // Assert - all should be instances of ModelSelectionCriteria
        assertNotNull(criteria1)
        assertNotNull(criteria2)
        assertNotNull(criteria3)
        assertNotNull(criteria4)
        assertNotNull(criteria5)
        assertNotNull(criteria6)
        assertNotNull(criteria7)
    }
}
