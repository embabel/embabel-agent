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
package com.embabel.agent.web.rest

import com.embabel.agent.core.AgentProcessStatusCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AgentProcessStatusTest {

    @Test
    fun `should create AgentProcessStatus with all parameters`() {
        // Arrange
        val timestamp = Instant.parse("2024-05-31T10:00:00Z")
        val runningTime = Duration.ofSeconds(30)

        // Act
        val status = AgentProcessStatus(
            id = "process-123",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = timestamp,
            runningTime = runningTime,
            result = null
        )

        // Assert
        assertEquals("process-123", status.id)
        assertEquals(AgentProcessStatusCode.RUNNING, status.status)
        assertEquals(timestamp, status.timestamp)
        assertEquals(runningTime, status.runningTime)
        assertNull(status.result)
        assertEquals("/api/v1/process/process-123", status.statusUrl)
        assertEquals("/events/process/process-123/status", status.sseUrl)
    }

    @Test
    fun `should create AgentProcessStatus with custom URLs`() {
        // Arrange
        val timestamp = Instant.now()
        val runningTime = Duration.ofMinutes(5)

        // Act
        val status = AgentProcessStatus(
            id = "process-456",
            status = AgentProcessStatusCode.COMPLETED,
            timestamp = timestamp,
            runningTime = runningTime,
            result = "Success",
            statusUrl = "/custom/status",
            sseUrl = "/custom/sse"
        )

        // Assert
        assertEquals("/custom/status", status.statusUrl)
        assertEquals("/custom/sse", status.sseUrl)
    }

    @Test
    fun `should have default statusUrl with process id`() {
        // Arrange & Act
        val status = AgentProcessStatus(
            id = "test-id",
            status = AgentProcessStatusCode.NOT_STARTED,
            timestamp = Instant.now(),
            runningTime = Duration.ZERO,
            result = null
        )

        // Assert
        assertEquals("/api/v1/process/test-id", status.statusUrl)
    }

    @Test
    fun `should have default sseUrl with process id`() {
        // Arrange & Act
        val status = AgentProcessStatus(
            id = "test-id",
            status = AgentProcessStatusCode.NOT_STARTED,
            timestamp = Instant.now(),
            runningTime = Duration.ZERO,
            result = null
        )

        // Assert
        assertEquals("/events/process/test-id/status", status.sseUrl)
    }

    @Test
    fun `should support different AgentProcessStatusCode values`() {
        // Arrange
        val timestamp = Instant.now()
        val runningTime = Duration.ofSeconds(10)

        // Act & Assert
        val pending = AgentProcessStatus("id1", AgentProcessStatusCode.NOT_STARTED, timestamp, runningTime, null)
        assertEquals(AgentProcessStatusCode.NOT_STARTED, pending.status)

        val running = AgentProcessStatus("id2", AgentProcessStatusCode.RUNNING, timestamp, runningTime, null)
        assertEquals(AgentProcessStatusCode.RUNNING, running.status)

        val completed = AgentProcessStatus("id3", AgentProcessStatusCode.COMPLETED, timestamp, runningTime, null)
        assertEquals(AgentProcessStatusCode.COMPLETED, completed.status)

        val failed = AgentProcessStatus("id4", AgentProcessStatusCode.FAILED, timestamp, runningTime, null)
        assertEquals(AgentProcessStatusCode.FAILED, failed.status)
    }

    @Test
    fun `should store result as Any type`() {
        // Arrange
        val timestamp = Instant.now()
        val runningTime = Duration.ofSeconds(15)
        val result = mapOf("key" to "value", "count" to 42)

        // Act
        val status = AgentProcessStatus(
            id = "process-789",
            status = AgentProcessStatusCode.COMPLETED,
            timestamp = timestamp,
            runningTime = runningTime,
            result = result
        )

        // Assert
        assertEquals(result, status.result)
    }

    @Test
    fun `should support copy with modified status`() {
        // Arrange
        val original = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = Instant.now(),
            runningTime = Duration.ofSeconds(10),
            result = null
        )

        // Act
        val modified = original.copy(status = AgentProcessStatusCode.COMPLETED)

        // Assert
        assertEquals(AgentProcessStatusCode.COMPLETED, modified.status)
        assertEquals(AgentProcessStatusCode.RUNNING, original.status)
    }

    @Test
    fun `should support copy with modified result`() {
        // Arrange
        val original = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = Instant.now(),
            runningTime = Duration.ofSeconds(10),
            result = null
        )

        // Act
        val modified = original.copy(result = "Completed successfully")

        // Assert
        assertEquals("Completed successfully", modified.result)
        assertNull(original.result)
    }

    @Test
    fun `should support copy with modified runningTime`() {
        // Arrange
        val original = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = Instant.now(),
            runningTime = Duration.ofSeconds(10),
            result = null
        )

        // Act
        val modified = original.copy(runningTime = Duration.ofSeconds(20))

        // Assert
        assertEquals(Duration.ofSeconds(20), modified.runningTime)
        assertEquals(Duration.ofSeconds(10), original.runningTime)
    }

    @Test
    fun `should implement Timestamped interface`() {
        // Arrange & Act
        val status = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = Instant.now(),
            runningTime = Duration.ofSeconds(10),
            result = null
        )

        // Assert
        assertTrue(status is com.embabel.common.core.types.Timestamped)
    }

    @Test
    fun `should implement Timed interface`() {
        // Arrange & Act
        val status = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = Instant.now(),
            runningTime = Duration.ofSeconds(10),
            result = null
        )

        // Assert
        assertTrue(status is com.embabel.common.core.types.Timed)
    }

    @Test
    fun `should implement OperationStatus interface`() {
        // Arrange & Act
        val status = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = Instant.now(),
            runningTime = Duration.ofSeconds(10),
            result = null
        )

        // Assert
        assertTrue(status is com.embabel.agent.core.OperationStatus<*>)
    }

    @Test
    fun `should handle zero running time`() {
        // Arrange & Act
        val status = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.NOT_STARTED,
            timestamp = Instant.now(),
            runningTime = Duration.ZERO,
            result = null
        )

        // Assert
        assertEquals(Duration.ZERO, status.runningTime)
    }

    @Test
    fun `should handle long running times`() {
        // Arrange
        val longDuration = Duration.ofHours(24)

        // Act
        val status = AgentProcessStatus(
            id = "process-1",
            status = AgentProcessStatusCode.RUNNING,
            timestamp = Instant.now(),
            runningTime = longDuration,
            result = null
        )

        // Assert
        assertEquals(longDuration, status.runningTime)
    }
}
