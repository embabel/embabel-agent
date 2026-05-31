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
package com.embabel.agent.domain.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SummaryTest {

    @Test
    fun `should create summary with text`() {
        // Arrange & Act
        val summary = Summary("This is a summary")

        // Assert
        assertEquals("This is a summary", summary.summary)
        assertEquals("This is a summary", summary.content)
    }

    @Test
    fun `should implement HasContent interface`() {
        // Arrange
        val summary = Summary("Test content")

        // Assert
        assertTrue(summary is HasContent)
        assertEquals("Test content", summary.content)
    }

    @Test
    fun `should support data class equality`() {
        // Arrange
        val summary1 = Summary("Same text")
        val summary2 = Summary("Same text")
        val summary3 = Summary("Different text")

        // Assert
        assertEquals(summary1, summary2)
        assertNotEquals(summary1, summary3)
    }
}
