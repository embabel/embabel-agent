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

import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.prompt.PromptContributionLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for ContextualPromptElement.
 *
 * These tests verify that the ContextualPromptElement correctly:
 * 1. Executes the contribution lambda when calling contribution()
 * 2. Does not cause StackOverflowException (the bug that was fixed)
 * 3. Creates proper PromptContribution objects
 * 4. Converts to PromptContributor correctly
 */
class ContextualPromptElementTest {

    @Test
    fun `should execute contribution lambda when calling contribution method`() {
        // Arrange
        val context = FakeOperationContext.create()
        var lambdaWasCalled = false

        val element = ContextualPromptElement.of(
            role = "system",
            location = PromptContributionLocation.BEGINNING
        ) { ctx ->
            lambdaWasCalled = true
            "Dynamic content for operation: ${ctx.operation}"
        }

        // Act
        val result = element.contribution(context)

        // Assert
        assertTrue(lambdaWasCalled, "Lambda should have been invoked")
        assertTrue(result.contains("test"), "Should contain operation name from context")
        assertTrue(result.contains("FakeAction"), "Should contain FakeAction in operation name")
    }

    @Test
    fun `should not cause StackOverflowException when calling contribution`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of { "Test content" }

        // Act & Assert - should not throw StackOverflowException
        assertDoesNotThrow {
            element.contribution(context)
        }
    }

    @Test
    fun `should create prompt contribution with context`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of(
            role = "user",
            location = PromptContributionLocation.BEGINNING
        ) { "Test content" }

        // Act
        val contribution = element.promptContribution(context)

        // Assert
        assertEquals("Test content", contribution.content)
        assertEquals(PromptContributionLocation.BEGINNING, contribution.location)
        assertEquals("user", contribution.role)
    }

    @Test
    fun `should create prompt contribution with null role`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of(
            role = null,
            location = PromptContributionLocation.BEGINNING
        ) { "Test content without role" }

        // Act
        val contribution = element.promptContribution(context)

        // Assert
        assertEquals("Test content without role", contribution.content)
        assertNull(contribution.role)
    }

    @Test
    fun `should convert to prompt contributor`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of(
            role = "user"
        ) { "User prompt" }

        // Act
        val contributor = element.toPromptContributor(context)

        // Assert - verify the contributor is created (non-null)
        assertNotNull(contributor)
    }

    @Test
    fun `should use default location when not specified`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of { "Default location" }

        // Act
        val contribution = element.promptContribution(context)

        // Assert
        assertEquals(PromptContributionLocation.BEGINNING, contribution.location)
    }

    @Test
    fun `should access context properties in lambda`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of { ctx ->
            "Operation: ${ctx.operation}, Agent: ${ctx.agentProcess.agent.name}"
        }

        // Act
        val result = element.contribution(context)

        // Assert
        assertTrue(result.contains("Operation:"))
        assertTrue(result.contains("test"))
        assertTrue(result.contains("Agent: Dummy Agent"))
    }

    @Test
    fun `should create element using invoke operator`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement(
            role = "system",
            location = PromptContributionLocation.BEGINNING
        ) { "Created via invoke" }

        // Act
        val result = element.contribution(context)

        // Assert
        assertEquals("Created via invoke", result)
    }

    @Test
    fun `should handle multiple invocations with different contexts`() {
        // Arrange
        val context1 = FakeOperationContext.create()
        val context2 = FakeOperationContext.create()
        var callCount = 0

        val element = ContextualPromptElement.of { ctx ->
            callCount++
            "Call ${callCount} for ${ctx.operation}"
        }

        // Act
        val result1 = element.contribution(context1)
        val result2 = element.contribution(context2)

        // Assert
        assertTrue(result1.contains("Call 1 for"))
        assertTrue(result1.contains("test"))
        assertTrue(result2.contains("Call 2 for"))
        assertTrue(result2.contains("test"))
        assertEquals(2, callCount)
    }

    @Test
    fun `should support complex contribution logic`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of { ctx ->
            buildString {
                appendLine("Context Information:")
                appendLine("- Operation: ${ctx.operation}")
                appendLine("- Agent: ${ctx.agentProcess.agent.name}")
                appendLine("- Provider: ${ctx.agentProcess.agent.provider}")
            }
        }

        // Act
        val result = element.contribution(context)

        // Assert
        assertTrue(result.contains("Context Information:"))
        assertTrue(result.contains("- Operation:"))
        assertTrue(result.contains("test"))
        assertTrue(result.contains("- Agent: Dummy Agent"))
    }

    @Test
    fun `should preserve role and location across multiple operations`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of(
            role = "assistant",
            location = PromptContributionLocation.BEGINNING
        ) { "Consistent metadata" }

        // Act
        val contribution1 = element.promptContribution(context)
        val contribution2 = element.promptContribution(context)

        // Assert
        assertEquals("assistant", contribution1.role)
        assertEquals("assistant", contribution2.role)
        assertEquals(PromptContributionLocation.BEGINNING, contribution1.location)
        assertEquals(PromptContributionLocation.BEGINNING, contribution2.location)
    }

    @Test
    fun `should handle empty string contribution`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of { "" }

        // Act
        val result = element.contribution(context)

        // Assert
        assertEquals("", result)
    }

    @Test
    fun `should handle multiline string contribution`() {
        // Arrange
        val context = FakeOperationContext.create()
        val element = ContextualPromptElement.of {
            """
            Line 1
            Line 2
            Line 3
            """.trimIndent()
        }

        // Act
        val result = element.contribution(context)

        // Assert
        assertTrue(result.contains("Line 1"))
        assertTrue(result.contains("Line 2"))
        assertTrue(result.contains("Line 3"))
    }
}
