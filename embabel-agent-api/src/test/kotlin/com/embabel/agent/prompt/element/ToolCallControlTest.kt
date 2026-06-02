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
package com.embabel.agent.prompt.element

import com.embabel.common.ai.prompt.PromptContributor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolCallControlTest {

    @Test
    fun `should create ToolCallControl with default toolCalls of 5`() {
        // Arrange & Act
        val control = ToolCallControl()

        // Assert
        assertEquals(5, control.toolCalls)
    }

    @Test
    fun `should create ToolCallControl with custom toolCalls`() {
        // Arrange & Act
        val control = ToolCallControl(toolCalls = 10)

        // Assert
        assertEquals(10, control.toolCalls)
    }

    @Test
    fun `should implement PromptContributor interface`() {
        // Arrange & Act
        val control = ToolCallControl()

        // Assert
        assertTrue(control is PromptContributor)
    }

    @Test
    fun `contribution should mention toolCalls limit`() {
        // Arrange
        val control = ToolCallControl(toolCalls = 7)

        // Act
        val contribution = control.contribution()

        // Assert
        assertTrue(contribution.contains("7"))
        assertTrue(contribution.contains("tool calls"))
    }

    @Test
    fun `contribution should contain guidance about limit`() {
        // Arrange
        val control = ToolCallControl(toolCalls = 3)

        // Act
        val contribution = control.contribution()

        // Assert
        assertTrue(contribution.contains("allowed"))
        assertTrue(contribution.contains("up to"))
    }

    @Test
    fun `should support copy with modified toolCalls`() {
        // Arrange
        val original = ToolCallControl(toolCalls = 5)

        // Act
        val modified = original.copy(toolCalls = 10)

        // Assert
        assertEquals(10, modified.toolCalls)
        assertEquals(5, original.toolCalls)
    }

    @Test
    fun `should support equality for ToolCallControl`() {
        // Arrange
        val control1 = ToolCallControl(toolCalls = 5)
        val control2 = ToolCallControl(toolCalls = 5)
        val control3 = ToolCallControl(toolCalls = 10)

        // Assert
        assertEquals(control1, control2)
        assertNotEquals(control1, control3)
    }

    @Test
    fun `should create FocusedToolCallControl with toolName and default toolCalls`() {
        // Arrange & Act
        val control = FocusedToolCallControl(toolName = "search")

        // Assert
        assertEquals("search", control.toolName)
        assertEquals(5, control.toolCalls)
    }

    @Test
    fun `should create FocusedToolCallControl with custom toolCalls`() {
        // Arrange & Act
        val control = FocusedToolCallControl(toolName = "analyze", toolCalls = 3)

        // Assert
        assertEquals("analyze", control.toolName)
        assertEquals(3, control.toolCalls)
    }

    @Test
    fun `FocusedToolCallControl should implement PromptContributor`() {
        // Arrange & Act
        val control = FocusedToolCallControl(toolName = "test")

        // Assert
        assertTrue(control is PromptContributor)
    }

    @Test
    fun `FocusedToolCallControl contribution should mention toolName and limit`() {
        // Arrange
        val control = FocusedToolCallControl(toolName = "database_query", toolCalls = 4)

        // Act
        val contribution = control.contribution()

        // Assert
        assertTrue(contribution.contains("database_query"))
        assertTrue(contribution.contains("4"))
    }

    @Test
    fun `FocusedToolCallControl contribution should contain focused guidance`() {
        // Arrange
        val control = FocusedToolCallControl(toolName = "search", toolCalls = 2)

        // Act
        val contribution = control.contribution()

        // Assert
        assertTrue(contribution.contains("calls to the"))
        assertTrue(contribution.contains("search"))
    }

    @Test
    fun `should support copy for FocusedToolCallControl`() {
        // Arrange
        val original = FocusedToolCallControl(toolName = "original", toolCalls = 3)

        // Act
        val modified = original.copy(toolName = "modified")

        // Assert
        assertEquals("modified", modified.toolName)
        assertEquals(3, modified.toolCalls)
        assertEquals("original", original.toolName)
    }

    @Test
    fun `should support equality for FocusedToolCallControl`() {
        // Arrange
        val control1 = FocusedToolCallControl(toolName = "test", toolCalls = 5)
        val control2 = FocusedToolCallControl(toolName = "test", toolCalls = 5)
        val control3 = FocusedToolCallControl(toolName = "different", toolCalls = 5)

        // Assert
        assertEquals(control1, control2)
        assertNotEquals(control1, control3)
    }
}
