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
package com.embabel.agent.rag.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class RagHintDataClassesTest {

    @Test
    fun `should create ResultCompression with default enabled true`() {
        // Arrange & Act
        val hint = ResultCompression()

        // Assert
        assertTrue(hint.enabled)
        assertTrue(hint is RagHint)
    }

    @Test
    fun `should create ResultCompression with enabled false`() {
        // Arrange & Act
        val hint = ResultCompression(enabled = false)

        // Assert
        assertFalse(hint.enabled)
    }

    @Test
    fun `should create ResultCompression with enabled true explicitly`() {
        // Arrange & Act
        val hint = ResultCompression(enabled = true)

        // Assert
        assertTrue(hint.enabled)
    }

    @Test
    fun `should create DesiredMaxLatency with duration`() {
        // Arrange
        val duration = Duration.ofSeconds(10)

        // Act
        val hint = DesiredMaxLatency(duration)

        // Assert
        assertEquals(duration, hint.duration)
        assertTrue(hint is RagHint)
    }

    @Test
    fun `should create DesiredMaxLatency using of factory method`() {
        // Arrange
        val duration = Duration.ofMillis(500)

        // Act
        val hint = DesiredMaxLatency.of(duration)

        // Assert
        assertEquals(duration, hint.duration)
    }

    @Test
    fun `should have UNBOUNDED constant with max duration`() {
        // Arrange & Act
        val unbounded = DesiredMaxLatency.UNBOUNDED

        // Assert
        assertNotNull(unbounded)
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), unbounded.duration)
    }

    @Test
    fun `should support copy for DesiredMaxLatency`() {
        // Arrange
        val original = DesiredMaxLatency(Duration.ofSeconds(5))

        // Act
        val modified = original.copy(duration = Duration.ofSeconds(10))

        // Assert
        assertEquals(Duration.ofSeconds(10), modified.duration)
        assertEquals(Duration.ofSeconds(5), original.duration)
    }

    @Test
    fun `should support equality for DesiredMaxLatency`() {
        // Arrange
        val duration = Duration.ofSeconds(5)
        val hint1 = DesiredMaxLatency(duration)
        val hint2 = DesiredMaxLatency(duration)
        val hint3 = DesiredMaxLatency(Duration.ofSeconds(10))

        // Assert
        assertEquals(hint1, hint2)
        assertNotEquals(hint1, hint3)
    }

    @Test
    fun `should create HyDE with context and default wordCount`() {
        // Arrange & Act
        val hint = HyDE(context = "The history of Rome")

        // Assert
        assertEquals("The history of Rome", hint.context)
        assertEquals(50, hint.wordCount)
        assertTrue(hint is RagHint)
    }

    @Test
    fun `should create HyDE with custom wordCount`() {
        // Arrange & Act
        val hint = HyDE(
            context = "Machine learning algorithms",
            wordCount = 100
        )

        // Assert
        assertEquals("Machine learning algorithms", hint.context)
        assertEquals(100, hint.wordCount)
    }

    @Test
    fun `should support copy for HyDE`() {
        // Arrange
        val original = HyDE(context = "original", wordCount = 50)

        // Act
        val modified = original.copy(wordCount = 75)

        // Assert
        assertEquals("original", modified.context)
        assertEquals(75, modified.wordCount)
        assertEquals(50, original.wordCount)
    }

    @Test
    fun `should support equality for HyDE`() {
        // Arrange
        val hint1 = HyDE(context = "test", wordCount = 50)
        val hint2 = HyDE(context = "test", wordCount = 50)
        val hint3 = HyDE(context = "different", wordCount = 50)

        // Assert
        assertEquals(hint1, hint2)
        assertNotEquals(hint1, hint3)
    }

    @Test
    fun `should allow zero wordCount for HyDE`() {
        // Arrange & Act
        val hint = HyDE(context = "test", wordCount = 0)

        // Assert
        assertEquals(0, hint.wordCount)
    }

    @Test
    fun `should allow large wordCount for HyDE`() {
        // Arrange & Act
        val hint = HyDE(context = "test", wordCount = 1000)

        // Assert
        assertEquals(1000, hint.wordCount)
    }
}
