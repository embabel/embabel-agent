/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
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
package com.embabel.agent.mcpserver.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ServerHealthStatusTest {

    @Test
    fun `should create ServerHealthStatus with all parameters`() {
        // Arrange
        val timestamp = Instant.now()
        val issues = listOf("Issue 1", "Issue 2")

        // Act
        val status = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = issues,
            timestamp = timestamp
        )

        // Assert
        assertTrue(status.isHealthy)
        assertEquals(McpExecutionMode.SYNC, status.mode)
        assertEquals(10, status.toolCount)
        assertEquals(issues, status.issues)
        assertEquals(timestamp, status.timestamp)
    }

    @Test
    fun `should create ServerHealthStatus with default timestamp`() {
        // Arrange
        val before = Instant.now()

        // Act
        val status = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.ASYNC,
            toolCount = 5,
            issues = emptyList()
        )
        val after = Instant.now()

        // Assert
        assertNotNull(status.timestamp)
        assertFalse(status.timestamp.isBefore(before))
        assertFalse(status.timestamp.isAfter(after))
    }

    @Test
    fun `should create healthy status with no issues`() {
        // Arrange & Act
        val status = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 15,
            issues = emptyList()
        )

        // Assert
        assertTrue(status.isHealthy)
        assertTrue(status.issues.isEmpty())
    }

    @Test
    fun `should create unhealthy status with issues`() {
        // Arrange
        val issues = listOf("Database connection failed", "Memory usage high")

        // Act
        val status = ServerHealthStatus(
            isHealthy = false,
            mode = McpExecutionMode.SYNC,
            toolCount = 8,
            issues = issues
        )

        // Assert
        assertFalse(status.isHealthy)
        assertEquals(2, status.issues.size)
        assertTrue(status.issues.contains("Database connection failed"))
        assertTrue(status.issues.contains("Memory usage high"))
    }

    @Test
    fun `should support copy with modified isHealthy`() {
        // Arrange
        val original = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = emptyList()
        )

        // Act
        val modified = original.copy(isHealthy = false)

        // Assert
        assertFalse(modified.isHealthy)
        assertTrue(original.isHealthy)
    }

    @Test
    fun `should support copy with modified mode`() {
        // Arrange
        val original = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = emptyList()
        )

        // Act
        val modified = original.copy(mode = McpExecutionMode.ASYNC)

        // Assert
        assertEquals(McpExecutionMode.ASYNC, modified.mode)
        assertEquals(McpExecutionMode.SYNC, original.mode)
    }

    @Test
    fun `should support copy with modified toolCount`() {
        // Arrange
        val original = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = emptyList()
        )

        // Act
        val modified = original.copy(toolCount = 20)

        // Assert
        assertEquals(20, modified.toolCount)
        assertEquals(10, original.toolCount)
    }

    @Test
    fun `should support copy with modified issues`() {
        // Arrange
        val original = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = emptyList()
        )
        val newIssues = listOf("New issue")

        // Act
        val modified = original.copy(issues = newIssues)

        // Assert
        assertEquals(1, modified.issues.size)
        assertTrue(original.issues.isEmpty())
    }

    @Test
    fun `should support copy with modified timestamp`() {
        // Arrange
        val time1 = Instant.parse("2024-05-31T10:00:00Z")
        val time2 = Instant.parse("2024-05-31T11:00:00Z")
        val original = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = emptyList(),
            timestamp = time1
        )

        // Act
        val modified = original.copy(timestamp = time2)

        // Assert
        assertEquals(time2, modified.timestamp)
        assertEquals(time1, original.timestamp)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val timestamp = Instant.parse("2024-05-31T10:00:00Z")
        val issues = listOf("Issue")
        val status1 = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = issues,
            timestamp = timestamp
        )
        val status2 = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = issues,
            timestamp = timestamp
        )
        val status3 = ServerHealthStatus(
            isHealthy = false,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = issues,
            timestamp = timestamp
        )

        // Assert
        assertEquals(status1, status2)
        assertNotEquals(status1, status3)
    }

    @Test
    fun `should handle zero tool count`() {
        // Arrange & Act
        val status = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 0,
            issues = emptyList()
        )

        // Assert
        assertEquals(0, status.toolCount)
    }

    @Test
    fun `should handle large tool count`() {
        // Arrange & Act
        val status = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 1000,
            issues = emptyList()
        )

        // Assert
        assertEquals(1000, status.toolCount)
    }

    @Test
    fun `should handle multiple issues`() {
        // Arrange
        val issues = (1..10).map { "Issue $it" }

        // Act
        val status = ServerHealthStatus(
            isHealthy = false,
            mode = McpExecutionMode.ASYNC,
            toolCount = 5,
            issues = issues
        )

        // Assert
        assertEquals(10, status.issues.size)
        assertEquals("Issue 1", status.issues[0])
        assertEquals("Issue 10", status.issues[9])
    }

    @Test
    fun `should work with SYNC mode`() {
        // Arrange & Act
        val status = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 5,
            issues = emptyList()
        )

        // Assert
        assertEquals(McpExecutionMode.SYNC, status.mode)
    }

    @Test
    fun `should work with ASYNC mode`() {
        // Arrange & Act
        val status = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.ASYNC,
            toolCount = 5,
            issues = emptyList()
        )

        // Assert
        assertEquals(McpExecutionMode.ASYNC, status.mode)
    }
}
