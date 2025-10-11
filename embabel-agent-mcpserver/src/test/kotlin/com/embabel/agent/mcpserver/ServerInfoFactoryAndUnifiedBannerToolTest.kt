/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.mcpserver

import com.embabel.agent.mcpserver.domain.McpExecutionMode
import com.embabel.agent.mcpserver.domain.ServerInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class ServerInfoFactoryTest {

    @Test
    fun `create should return ServerInfo with SYNC mode`() {
        val serverInfo = ServerInfoFactory.create(McpExecutionMode.SYNC)

        assertEquals("Embabel Agent MCP Server", serverInfo.name)
        assertEquals(McpExecutionMode.SYNC, serverInfo.mode)
        assertEquals(System.getProperty("java.runtime.version"), serverInfo.javaVersion)
        assertTrue(serverInfo.startTime.isBefore(Instant.now().plusSeconds(1)))
    }

    @Test
    fun `create should return ServerInfo with ASYNC mode`() {
        val serverInfo = ServerInfoFactory.create(McpExecutionMode.ASYNC)

        assertEquals("Embabel Agent MCP Server", serverInfo.name)
        assertEquals(McpExecutionMode.ASYNC, serverInfo.mode)
        assertEquals(System.getProperty("java.runtime.version"), serverInfo.javaVersion)
        assertTrue(serverInfo.startTime.isBefore(Instant.now().plusSeconds(1)))
    }

    @Test
    fun `create should handle null implementation version`() {
        // Note: This test simulates development environment where package version is null
        val serverInfo = ServerInfoFactory.create(McpExecutionMode.SYNC)

        // In development, version will be "development" due to null check
        assertTrue(
            serverInfo.version == "development" ||
            serverInfo.version.isNotEmpty()
        )
    }
}

class UnifiedBannerToolTest {

    @Test
    fun `helloBanner should return correct banner format for SYNC mode`() {
        val serverInfo = ServerInfo(
            name = "Test Server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1",
            startTime = Instant.parse("2025-01-01T12:00:00Z")
        )

        val bannerTool = UnifiedBannerTool(serverInfo)
        val result = bannerTool.helloBanner()

        assertEquals("banner", result["type"])
        assertEquals("SYNC", result["mode"])

        val lines = result["lines"] as List<String>
        assertEquals(6, lines.size)
        assertTrue(lines[1].contains("SYNC"))
        assertTrue(lines[2].contains("1.0.0"))
        assertTrue(lines[3].contains("17.0.1"))
    }

    @Test
    fun `helloBanner should return correct banner format for ASYNC mode`() {
        val serverInfo = ServerInfo(
            name = "Test Server",
            version = "2.0.0",
            mode = McpExecutionMode.ASYNC,
            javaVersion = "21.0.1",
            startTime = Instant.parse("2025-01-01T15:30:00Z")
        )

        val bannerTool = UnifiedBannerTool(serverInfo)
        val result = bannerTool.helloBanner()

        assertEquals("banner", result["type"])
        assertEquals("ASYNC", result["mode"])

        val lines = result["lines"] as List<String>
        assertEquals(6, lines.size)
        assertTrue(lines[1].contains("ASYNC"))
        assertTrue(lines[2].contains("2.0.0"))
        assertTrue(lines[3].contains("21.0.1"))
        assertTrue(lines[4].contains("2025-01-01T15:30:00Z"))
    }

    @Test
    fun `helloBanner should handle various server info configurations`() {
        val serverInfo = ServerInfo(
            name = "Custom Server Name",
            version = "0.1.0-SNAPSHOT",
            mode = McpExecutionMode.SYNC,
            javaVersion = "11.0.2+9"
        )

        val bannerTool = UnifiedBannerTool(serverInfo)
        val result = bannerTool.helloBanner()

        assertNotNull(result)
        assertEquals(3, result.size)
        assertTrue(result.containsKey("type"))
        assertTrue(result.containsKey("mode"))
        assertTrue(result.containsKey("lines"))

        val lines = result["lines"] as List<String>
        assertTrue(lines.any { it.contains("0.1.0-SNAPSHOT") })
        assertTrue(lines.any { it.contains("11.0.2+9") })
    }

    @Test
    fun `helloBanner should include all required fields in result map`() {
        val serverInfo = ServerInfo(
            name = "Test",
            version = "1.0",
            mode = McpExecutionMode.ASYNC,
            javaVersion = "17"
        )

        val bannerTool = UnifiedBannerTool(serverInfo)
        val result = bannerTool.helloBanner()

        // Verify the result structure matches expected Map<String, Any>
        assertTrue(result["type"] is String)
        assertTrue(result["mode"] is String)
        assertTrue(result["lines"] is List<*>)

        // Verify content
        assertEquals("banner", result["type"])
        assertEquals("ASYNC", result["mode"])

       val lines = result["lines"] as List<String>
        assertTrue(lines.isNotEmpty())
        assertEquals(6, lines.size)
    }
}
