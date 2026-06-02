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
package com.embabel.agent.mcpserver.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ServerInfoTest {

    @Test
    fun `should create ServerInfo with required parameters`() {
        // Arrange
        val startTime = Instant.now()

        // Act
        val serverInfo = ServerInfo(
            name = "test-server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1",
            startTime = startTime
        )

        // Assert
        assertEquals("test-server", serverInfo.name)
        assertEquals("1.0.0", serverInfo.version)
        assertEquals(McpExecutionMode.SYNC, serverInfo.mode)
        assertEquals("17.0.1", serverInfo.javaVersion)
        assertEquals(startTime, serverInfo.startTime)
    }

    @Test
    fun `should create ServerInfo with default startTime`() {
        // Arrange
        val before = Instant.now()

        // Act
        val serverInfo = ServerInfo(
            name = "test-server",
            version = "1.0.0",
            mode = McpExecutionMode.ASYNC,
            javaVersion = "17.0.1"
        )
        val after = Instant.now()

        // Assert
        assertNotNull(serverInfo.startTime)
        assertFalse(serverInfo.startTime.isBefore(before))
        assertFalse(serverInfo.startTime.isAfter(after))
    }

    @Test
    fun `should create banner lines with correct format`() {
        // Arrange
        val startTime = Instant.parse("2024-05-31T10:00:00Z")
        val serverInfo = ServerInfo(
            name = "embabel-server",
            version = "2.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "21.0.0",
            startTime = startTime
        )

        // Act
        val bannerLines = serverInfo.toBannerLines()

        // Assert
        assertEquals(6, bannerLines.size)
        assertTrue(bannerLines[0].contains("~"))
        assertTrue(bannerLines[1].contains("Embabel Agent MCP"))
        assertTrue(bannerLines[1].contains("SYNC"))
        assertTrue(bannerLines[2].contains("Version: 2.0.0"))
        assertTrue(bannerLines[3].contains("Java: 21.0.0"))
        assertTrue(bannerLines[4].contains("Started:"))
        assertTrue(bannerLines[5].contains("~"))
    }

    @Test
    fun `should create banner with ASYNC mode`() {
        // Arrange
        val serverInfo = ServerInfo(
            name = "test-server",
            version = "1.0.0",
            mode = McpExecutionMode.ASYNC,
            javaVersion = "17.0.1"
        )

        // Act
        val bannerLines = serverInfo.toBannerLines()

        // Assert
        assertTrue(bannerLines[1].contains("ASYNC"))
    }

    @Test
    fun `should support copy with modified name`() {
        // Arrange
        val original = ServerInfo(
            name = "original",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1"
        )

        // Act
        val modified = original.copy(name = "modified")

        // Assert
        assertEquals("modified", modified.name)
        assertEquals("original", original.name)
    }

    @Test
    fun `should support copy with modified version`() {
        // Arrange
        val original = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1"
        )

        // Act
        val modified = original.copy(version = "2.0.0")

        // Assert
        assertEquals("2.0.0", modified.version)
        assertEquals("1.0.0", original.version)
    }

    @Test
    fun `should support copy with modified mode`() {
        // Arrange
        val original = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1"
        )

        // Act
        val modified = original.copy(mode = McpExecutionMode.ASYNC)

        // Assert
        assertEquals(McpExecutionMode.ASYNC, modified.mode)
        assertEquals(McpExecutionMode.SYNC, original.mode)
    }

    @Test
    fun `should support copy with modified javaVersion`() {
        // Arrange
        val original = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1"
        )

        // Act
        val modified = original.copy(javaVersion = "21.0.0")

        // Assert
        assertEquals("21.0.0", modified.javaVersion)
        assertEquals("17.0.1", original.javaVersion)
    }

    @Test
    fun `should support copy with modified startTime`() {
        // Arrange
        val time1 = Instant.parse("2024-05-31T10:00:00Z")
        val time2 = Instant.parse("2024-05-31T11:00:00Z")
        val original = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1",
            startTime = time1
        )

        // Act
        val modified = original.copy(startTime = time2)

        // Assert
        assertEquals(time2, modified.startTime)
        assertEquals(time1, original.startTime)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val time = Instant.parse("2024-05-31T10:00:00Z")
        val info1 = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1",
            startTime = time
        )
        val info2 = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1",
            startTime = time
        )
        val info3 = ServerInfo(
            name = "different",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1",
            startTime = time
        )

        // Assert
        assertEquals(info1, info2)
        assertNotEquals(info1, info3)
    }

    @Test
    fun `should have consistent banner separator length`() {
        // Arrange
        val serverInfo = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1"
        )

        // Act
        val bannerLines = serverInfo.toBannerLines()

        // Assert
        assertEquals(50, bannerLines[0].length)
        assertEquals(50, bannerLines[5].length)
        assertEquals(bannerLines[0], bannerLines[5])
    }

    @Test
    fun `should handle different Java versions in banner`() {
        // Arrange
        val serverInfo = ServerInfo(
            name = "server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "11.0.20"
        )

        // Act
        val bannerLines = serverInfo.toBannerLines()

        // Assert
        assertTrue(bannerLines[3].contains("11.0.20"))
    }
}
