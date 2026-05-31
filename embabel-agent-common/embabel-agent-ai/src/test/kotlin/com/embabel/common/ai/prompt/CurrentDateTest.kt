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
package com.embabel.common.ai.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CurrentDateTest {

    @Test
    fun `should generate contribution with current date`() {
        // Arrange
        val currentDate = CurrentDate()

        // Act
        val contribution = currentDate.contribution()

        // Assert
        assertTrue(contribution.startsWith("Current date:"))
        assertTrue(contribution.contains(LocalDate.now().year.toString()))
    }

    @Test
    fun `should use default yyyy-MM-dd format`() {
        // Arrange
        val currentDate = CurrentDate()
        val today = LocalDate.now()
        val expected = "Current date: ${today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}\n"

        // Act
        val contribution = currentDate.contribution()

        // Assert
        assertEquals(expected, contribution)
    }

    @Test
    fun `should use custom formatter`() {
        // Arrange
        val customFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val currentDate = CurrentDate(customFormatter)
        val today = LocalDate.now()
        val expected = "Current date: ${today.format(customFormatter)}\n"

        // Act
        val contribution = currentDate.contribution()

        // Assert
        assertEquals(expected, contribution)
    }

    @Test
    fun `should have correct role`() {
        // Arrange
        val currentDate = CurrentDate()

        // Act
        val role = currentDate.role

        // Assert
        assertEquals(PromptContribution.CURRENT_DATE_ROLE, role)
    }

    @Test
    fun `should have meaningful toString`() {
        // Arrange
        val currentDate = CurrentDate()

        // Act
        val string = currentDate.toString()

        // Assert
        assertTrue(string.startsWith("CurrentDate: ["))
        assertTrue(string.contains("Current date:"))
        assertTrue(string.endsWith("]"))
    }

    @Test
    fun `should implement PromptContributor interface`() {
        // Arrange
        val currentDate = CurrentDate()

        // Assert
        assertTrue(currentDate is PromptContributor)
    }
}
