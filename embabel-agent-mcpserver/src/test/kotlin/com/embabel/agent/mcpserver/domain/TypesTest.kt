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
package com.embabel.agent.mcpserver.domain

import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import kotlin.test.assertEquals

class TypesTest {

    @Test
    fun `McpExecutionMode should contain SYNC and ASYNC values`() {
        val modes = McpExecutionMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(McpExecutionMode.SYNC))
        assertTrue(modes.contains(McpExecutionMode.ASYNC))
    }

    @Test
    fun `ServerInfo should create banner lines correctly`() {
        val startTime = Instant.parse("2025-01-01T12:00:00Z")
        val serverInfo = ServerInfo(
            name = "Test Server",
            version = "1.0.0",
            mode = McpExecutionMode.SYNC,
            javaVersion = "17.0.1",
            startTime = startTime
        )

        val bannerLines = serverInfo.toBannerLines()

        assertEquals(6, bannerLines.size)
        assertEquals("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~", bannerLines[0])
        assertEquals("Embabel Agent MCP SYNC Server", bannerLines[1])
        assertEquals("Version: 1.0.0", bannerLines[2])
        assertEquals("Java: 17.0.1", bannerLines[3])
        assertEquals("Started: $startTime", bannerLines[4])
        assertEquals("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~", bannerLines[5])
    }

    @Test
    fun `ServerInfo should use current time by default`() {
        val beforeCreation = Instant.now()
        val serverInfo = ServerInfo(
            name = "Test Server",
            version = "1.0.0",
            mode = McpExecutionMode.ASYNC,
            javaVersion = "17.0.1"
        )
        val afterCreation = Instant.now()

        assertTrue(serverInfo.startTime.isAfter(beforeCreation) || serverInfo.startTime == beforeCreation)
        assertTrue(serverInfo.startTime.isBefore(afterCreation) || serverInfo.startTime == afterCreation)
    }

    @Test
    fun `ToolSpecification should return correct tool name and description`() {
        val mockTool = mockk<McpSchema.Tool>()
        every { mockTool.name() } returns "testTool"
        every { mockTool.description() } returns "Test tool description"

        val toolSpec = object : ToolSpecification<String> {
            override val tool: McpSchema.Tool = mockTool
            override val handler: String = "testHandler"
        }

        assertEquals("testTool", toolSpec.toolName())
        assertEquals("Test tool description", toolSpec.toolDescription())
    }

    @Test
    fun `ToolSpecification should handle null description`() {
        val mockTool = mockk<McpSchema.Tool>()
        every { mockTool.name() } returns "testTool"
        every { mockTool.description() } returns null

        val toolSpec = object : ToolSpecification<String> {
            override val tool: McpSchema.Tool = mockTool
            override val handler: String = "testHandler"
        }

        assertEquals("testTool", toolSpec.toolName())
        assertEquals("No description", toolSpec.toolDescription())
    }

    @Test
    fun `McpCapability should contain all expected values`() {
        val capabilities = McpCapability.values()
        assertEquals(5, capabilities.size)
        assertTrue(capabilities.contains(McpCapability.TOOLS))
        assertTrue(capabilities.contains(McpCapability.RESOURCES))
        assertTrue(capabilities.contains(McpCapability.PROMPTS))
        assertTrue(capabilities.contains(McpCapability.LOGGING))
        assertTrue(capabilities.contains(McpCapability.COMPLETIONS))
    }

    @Test
    fun `ServerHealthStatus should create with all properties`() {
        val timestamp = Instant.parse("2025-01-01T12:00:00Z")
        val issues = listOf("Issue 1", "Issue 2")

        val healthStatus = ServerHealthStatus(
            isHealthy = false,
            mode = McpExecutionMode.ASYNC,
            toolCount = 5,
            issues = issues,
            timestamp = timestamp
        )

        assertFalse(healthStatus.isHealthy)
        assertEquals(McpExecutionMode.ASYNC, healthStatus.mode)
        assertEquals(5, healthStatus.toolCount)
        assertEquals(issues, healthStatus.issues)
        assertEquals(timestamp, healthStatus.timestamp)
    }

    @Test
    fun `ServerHealthStatus should use current time by default`() {
        val beforeCreation = Instant.now()
        val healthStatus = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 3,
            issues = emptyList()
        )
        val afterCreation = Instant.now()

        assertTrue(healthStatus.timestamp.isAfter(beforeCreation) || healthStatus.timestamp == beforeCreation)
        assertTrue(healthStatus.timestamp.isBefore(afterCreation) || healthStatus.timestamp == afterCreation)
    }

    @Test
    fun `ServerHealthStatus should handle healthy status correctly`() {
        val healthStatus = ServerHealthStatus(
            isHealthy = true,
            mode = McpExecutionMode.SYNC,
            toolCount = 10,
            issues = emptyList()
        )

        assertTrue(healthStatus.isHealthy)
        assertTrue(healthStatus.issues.isEmpty())
    }

    @Test
    fun `ServerHealthStatus should handle unhealthy status with issues`() {
        val issues = listOf("Database connection failed", "Service timeout")
        val healthStatus = ServerHealthStatus(
            isHealthy = false,
            mode = McpExecutionMode.ASYNC,
            toolCount = 0,
            issues = issues
        )

        assertFalse(healthStatus.isHealthy)
        assertEquals(2, healthStatus.issues.size)
        assertEquals("Database connection failed", healthStatus.issues[0])
        assertEquals("Service timeout", healthStatus.issues[1])
    }
}
