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
package com.embabel.agent.api.common

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolsStatsTest {

    private class TestToolsStats(
        override val toolsStats: Map<String, ToolStats>
    ) : ToolsStats

    @Test
    fun `should create ToolsStats with empty map`() {
        // Arrange & Act
        val toolsStats = TestToolsStats(emptyMap())

        // Assert
        assertNotNull(toolsStats.toolsStats)
        assertEquals(0, toolsStats.toolsStats.size)
    }

    @Test
    fun `should create ToolsStats with single tool`() {
        // Arrange
        val toolStats = mockk<ToolStats>()
        val map = mapOf("tool1" to toolStats)

        // Act
        val toolsStats = TestToolsStats(map)

        // Assert
        assertEquals(1, toolsStats.toolsStats.size)
        assertEquals(toolStats, toolsStats.toolsStats["tool1"])
    }

    @Test
    fun `should create ToolsStats with multiple tools`() {
        // Arrange
        val tool1 = mockk<ToolStats>()
        val tool2 = mockk<ToolStats>()
        val map = mapOf("tool1" to tool1, "tool2" to tool2)

        // Act
        val toolsStats = TestToolsStats(map)

        // Assert
        assertEquals(2, toolsStats.toolsStats.size)
        assertEquals(tool1, toolsStats.toolsStats["tool1"])
        assertEquals(tool2, toolsStats.toolsStats["tool2"])
    }

    @Test
    fun `infoString should format empty map`() {
        // Arrange
        val toolsStats = TestToolsStats(emptyMap())

        // Act
        val info = toolsStats.infoString(false, 0)

        // Assert
        assertTrue(info.startsWith("Tool usage:"))
        assertEquals("Tool usage:\n", info)
    }

    @Test
    fun `infoString should format single tool`() {
        // Arrange
        val toolStats = mockk<ToolStats>()
        every { toolStats.infoString(any(), any()) } returns "ToolStats(name=tool1)"
        val toolsStats = TestToolsStats(mapOf("tool1" to toolStats))

        // Act
        val info = toolsStats.infoString(false, 0)

        // Assert
        assertTrue(info.contains("Tool usage:"))
        assertTrue(info.contains("ToolStats(name=tool1)"))
        verify { toolStats.infoString(false, 1) }
    }

    @Test
    fun `infoString should format multiple tools`() {
        // Arrange
        val tool1 = mockk<ToolStats>()
        val tool2 = mockk<ToolStats>()
        every { tool1.infoString(any(), any()) } returns "ToolStats(name=tool1)"
        every { tool2.infoString(any(), any()) } returns "ToolStats(name=tool2)"
        val toolsStats = TestToolsStats(mapOf("tool1" to tool1, "tool2" to tool2))

        // Act
        val info = toolsStats.infoString(false, 0)

        // Assert
        assertTrue(info.contains("Tool usage:"))
        assertTrue(info.contains("tool1"))
        assertTrue(info.contains("tool2"))
    }

    @Test
    fun `infoString should include line breaks between tools`() {
        // Arrange
        val tool1 = mockk<ToolStats>()
        val tool2 = mockk<ToolStats>()
        every { tool1.infoString(any(), any()) } returns "ToolStats(name=tool1)"
        every { tool2.infoString(any(), any()) } returns "ToolStats(name=tool2)"
        val toolsStats = TestToolsStats(mapOf("tool1" to tool1, "tool2" to tool2))

        // Act
        val info = toolsStats.infoString(true, 0)

        // Assert
        assertTrue(info.contains("\n"))
        assertTrue(info.contains(",\n"))
    }

    @Test
    fun `infoString should pass verbose parameter to tool stats`() {
        // Arrange
        val toolStats = mockk<ToolStats>()
        every { toolStats.infoString(any(), any()) } returns "ToolStats(verbose)"
        val toolsStats = TestToolsStats(mapOf("tool" to toolStats))

        // Act
        toolsStats.infoString(true, 0)

        // Assert
        verify { toolStats.infoString(true, 1) }
    }

    @Test
    fun `infoString should pass indent parameter to tool stats`() {
        // Arrange
        val toolStats = mockk<ToolStats>()
        every { toolStats.infoString(any(), any()) } returns "ToolStats(indented)"
        val toolsStats = TestToolsStats(mapOf("tool" to toolStats))

        // Act
        toolsStats.infoString(false, 5)

        // Assert
        verify { toolStats.infoString(false, 1) }
    }

    @Test
    fun `infoString should include tab indentation`() {
        // Arrange
        val toolStats = mockk<ToolStats>()
        every { toolStats.infoString(any(), any()) } returns "stats"
        val toolsStats = TestToolsStats(mapOf("tool" to toolStats))

        // Act
        val info = toolsStats.infoString(false, 2)

        // Assert
        assertTrue(info.contains("\tstats"))
    }

    @Test
    fun `should be HasInfoString`() {
        // Arrange
        val toolsStats = TestToolsStats(emptyMap())

        // Assert
        assertTrue(toolsStats is com.embabel.common.core.types.HasInfoString)
    }

    @Test
    fun `should handle tool retrieval by key`() {
        // Arrange
        val toolStats = mockk<ToolStats>()
        val toolsStats = TestToolsStats(mapOf("myTool" to toolStats))

        // Act
        val retrieved = toolsStats.toolsStats["myTool"]

        // Assert
        assertNotNull(retrieved)
        assertEquals(toolStats, retrieved)
    }

    @Test
    fun `should return null for missing tool`() {
        // Arrange
        val toolsStats = TestToolsStats(emptyMap())

        // Act
        val retrieved = toolsStats.toolsStats["nonexistent"]

        // Assert
        assertNull(retrieved)
    }

    @Test
    fun `infoString should handle null verbose parameter`() {
        // Arrange
        val toolStats = mockk<ToolStats>()
        every { toolStats.infoString(any(), any()) } returns "ToolStats"
        val toolsStats = TestToolsStats(mapOf("tool" to toolStats))

        // Act
        toolsStats.infoString(null, 0)

        // Assert
        verify { toolStats.infoString(null, 1) }
    }

    @Test
    fun `should support map iteration`() {
        // Arrange
        val tool1 = mockk<ToolStats>()
        val tool2 = mockk<ToolStats>()
        val toolsStats = TestToolsStats(mapOf("tool1" to tool1, "tool2" to tool2))

        // Act
        val keys = toolsStats.toolsStats.keys.toList()

        // Assert
        assertEquals(2, keys.size)
        assertTrue(keys.contains("tool1"))
        assertTrue(keys.contains("tool2"))
    }
}
