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
package com.embabel.agent.rag.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DualShotConfigTest {

    @Test
    fun `should create with default summaryWords of 100`() {
        // Arrange & Act
        val config = DualShotConfig()

        // Assert
        assertEquals(100, config.summaryWords)
    }

    @Test
    fun `should create with custom summaryWords`() {
        // Arrange & Act
        val config = DualShotConfig(summaryWords = 200)

        // Assert
        assertEquals(200, config.summaryWords)
    }

    @Test
    fun `should support copy with modified summaryWords`() {
        // Arrange
        val original = DualShotConfig(summaryWords = 100)

        // Act
        val modified = original.copy(summaryWords = 150)

        // Assert
        assertEquals(150, modified.summaryWords)
        assertEquals(100, original.summaryWords)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val config1 = DualShotConfig(summaryWords = 100)
        val config2 = DualShotConfig(summaryWords = 100)
        val config3 = DualShotConfig(summaryWords = 200)

        // Assert
        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
    }

    @Test
    fun `should allow zero summaryWords`() {
        // Arrange & Act
        val config = DualShotConfig(summaryWords = 0)

        // Assert
        assertEquals(0, config.summaryWords)
    }

    @Test
    fun `should allow large summaryWords`() {
        // Arrange & Act
        val config = DualShotConfig(summaryWords = 1000)

        // Assert
        assertEquals(1000, config.summaryWords)
    }

    @Test
    fun `should allow negative summaryWords`() {
        // Arrange & Act
        val config = DualShotConfig(summaryWords = -1)

        // Assert
        assertEquals(-1, config.summaryWords)
    }
}
