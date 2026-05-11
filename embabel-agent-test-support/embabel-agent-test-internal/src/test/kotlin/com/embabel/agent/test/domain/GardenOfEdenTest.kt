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
package com.embabel.agent.test.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the data fixtures defined in [gardenOfEden.kt].
 */
class GardenOfEdenTest {

    @Test
    fun `GeneratedName preserves constructor values and supports equality`() {
        // Arrange
        val generatedName = GeneratedName(name = "money.com", reason = "Helps make money")

        // Act
        val sameGeneratedName = GeneratedName(name = "money.com", reason = "Helps make money")
        val differentGeneratedName = GeneratedName(name = "garden.io", reason = "Helps make money")

        // Assert
        assertEquals("money.com", generatedName.name)
        assertEquals("Helps make money", generatedName.reason)
        assertEquals(sameGeneratedName, generatedName)
        assertFalse(generatedName == differentGeneratedName)
    }

    @Test
    fun `GeneratedNames holds an ordered list of generated names`() {
        // Arrange
        val names = listOf(
            GeneratedName("money.com", "Helps make money"),
            GeneratedName("garden.io", "Helps make gardening easier"),
        )

        // Act
        val generatedNames = GeneratedNames(names)

        // Assert
        assertEquals(names, generatedNames.names)
        assertEquals(2, generatedNames.names.size)
    }

    @Test
    fun `AllNames separates accepted and rejected names`() {
        // Arrange
        val accepted = listOf(GeneratedName("money.com", "Helps make money"))
        val rejected = listOf(GeneratedName("bad.biz", "Too vague"))

        // Act
        val allNames = AllNames(accepted = accepted, rejected = rejected)

        // Assert
        assertEquals(accepted, allNames.accepted)
        assertEquals(rejected, allNames.rejected)
    }

    @Test
    fun `Garden stores its name`() {
        // Arrange

        // Act
        val garden = Garden("Eden")

        // Assert
        assertEquals("Eden", garden.name)
    }

    @Test
    fun `SpiPerson stores its name`() {
        // Arrange

        // Act
        val person = SpiPerson("Rod")

        // Assert
        assertEquals("Rod", person.name)
    }

    @Test
    fun `WierdPerson stores all fields`() {
        // Arrange

        // Act
        val person = WierdPerson(name = "Marmaduke", age = 24, weirdness = "weird")

        // Assert
        assertEquals("Marmaduke", person.name)
        assertEquals(24, person.age)
        assertEquals("weird", person.weirdness)
    }

    @Test
    fun `Return preserves result success and captured prompt`() {
        // Arrange
        val result = Result.success("frog")

        // Act
        val returned = Return(result = result, capturedPrompt = "Create a frog")

        // Assert
        assertTrue(returned.result.isSuccess)
        assertEquals("frog", returned.result.getOrNull())
        assertEquals("Create a frog", returned.capturedPrompt)
    }

    @Test
    fun `Return supports failure results and copy`() {
        // Arrange
        val failure = IllegalStateException("bad prompt")
        val original = Return(result = Result.failure<String>(failure), capturedPrompt = "Broken prompt")

        // Act
        val copied = original.copy(capturedPrompt = "Retried prompt")

        // Assert
        assertTrue(original.result.isFailure)
        assertSame(failure, original.result.exceptionOrNull())
        assertTrue(copied.result.isFailure)
        assertSame(failure, copied.result.exceptionOrNull())
        assertEquals("Retried prompt", copied.capturedPrompt)
    }
}
