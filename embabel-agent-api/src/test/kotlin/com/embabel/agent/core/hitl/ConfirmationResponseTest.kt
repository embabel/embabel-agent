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
package com.embabel.agent.core.hitl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ConfirmationResponseTest {

    @Test
    fun `should create with required fields`() {
        // Arrange & Act
        val response = ConfirmationResponse(
            awaitableId = "awaitable-123",
            accepted = true
        )

        // Assert
        assertNotNull(response.id)
        assertEquals("awaitable-123", response.awaitableId)
        assertTrue(response.accepted)
        assertFalse(response.persistent())
        assertNotNull(response.timestamp)
    }

    @Test
    fun `should create with all fields`() {
        // Arrange
        val customId = "custom-id"
        val awaitableId = "awaitable-456"
        val timestamp = Instant.now()

        // Act
        val response = ConfirmationResponse(
            id = customId,
            awaitableId = awaitableId,
            accepted = false,
            persistent = true,
            timestamp = timestamp
        )

        // Assert
        assertEquals(customId, response.id)
        assertEquals(awaitableId, response.awaitableId)
        assertFalse(response.accepted)
        assertTrue(response.persistent())
        assertEquals(timestamp, response.timestamp)
    }

    @Test
    fun `should generate unique IDs by default`() {
        // Arrange & Act
        val response1 = ConfirmationResponse(awaitableId = "test", accepted = true)
        val response2 = ConfirmationResponse(awaitableId = "test", accepted = true)

        // Assert
        assertNotEquals(response1.id, response2.id)
    }

    @Test
    fun `should default persistent to false`() {
        // Arrange & Act
        val response = ConfirmationResponse(
            awaitableId = "test",
            accepted = true
        )

        // Assert
        assertFalse(response.persistent())
    }

    @Test
    fun `should handle accepted true`() {
        // Arrange & Act
        val response = ConfirmationResponse(
            awaitableId = "test",
            accepted = true
        )

        // Assert
        assertTrue(response.accepted)
    }

    @Test
    fun `should handle accepted false`() {
        // Arrange & Act
        val response = ConfirmationResponse(
            awaitableId = "test",
            accepted = false
        )

        // Assert
        assertFalse(response.accepted)
    }

    @Test
    fun `should support copy with modified fields`() {
        // Arrange
        val original = ConfirmationResponse(
            awaitableId = "original",
            accepted = true
        )

        // Act
        val modified = original.copy(accepted = false)

        // Assert
        assertEquals(original.id, modified.id)
        assertEquals(original.awaitableId, modified.awaitableId)
        assertFalse(modified.accepted)
        assertTrue(original.accepted)
    }

    @Test
    fun `should implement AwaitableResponse interface`() {
        // Arrange & Act
        val response = ConfirmationResponse(
            awaitableId = "test",
            accepted = true
        )

        // Assert
        assertTrue(response is AwaitableResponse)
    }

    @Test
    fun `should support equality comparison`() {
        // Arrange
        val timestamp = Instant.now()
        val response1 = ConfirmationResponse(
            id = "id-1",
            awaitableId = "awaitable",
            accepted = true,
            timestamp = timestamp
        )
        val response2 = ConfirmationResponse(
            id = "id-1",
            awaitableId = "awaitable",
            accepted = true,
            timestamp = timestamp
        )
        val response3 = ConfirmationResponse(
            id = "id-2",
            awaitableId = "awaitable",
            accepted = true,
            timestamp = timestamp
        )

        // Assert
        assertEquals(response1, response2)
        assertNotEquals(response1, response3)
    }
}
