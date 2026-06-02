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
package com.embabel.agent.prompt.persona

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InstructionTest {

    @Test
    fun `should create Instruction with instruction text`() {
        // Arrange & Act
        val instruction = Instruction("Follow these steps")

        // Assert
        assertEquals("Follow these steps", instruction.instruction)
    }

    @Test
    fun `should return instruction text as contribution`() {
        // Arrange
        val instruction = Instruction("Be helpful and concise")

        // Act
        val contribution = instruction.contribution()

        // Assert
        assertEquals("Be helpful and concise", contribution)
    }

    @Test
    fun `should create prompt contribution with instruction content`() {
        // Arrange
        val instruction = Instruction("Analyze the code carefully")

        // Act
        val promptContribution = instruction.promptContribution()

        // Assert
        assertEquals("Analyze the code carefully", promptContribution.content)
    }

    @Test
    fun `should support copy with modified instruction`() {
        // Arrange
        val original = Instruction("Original instruction")

        // Act
        val modified = original.copy(instruction = "Modified instruction")

        // Assert
        assertEquals("Modified instruction", modified.instruction)
        assertEquals("Original instruction", original.instruction)
    }

    @Test
    fun `should support equality`() {
        // Arrange
        val instruction1 = Instruction("Same instruction")
        val instruction2 = Instruction("Same instruction")
        val instruction3 = Instruction("Different instruction")

        // Assert
        assertEquals(instruction1, instruction2)
        assertNotEquals(instruction1, instruction3)
    }

    @Test
    fun `should handle empty instruction`() {
        // Arrange & Act
        val instruction = Instruction("")

        // Assert
        assertEquals("", instruction.instruction)
        assertEquals("", instruction.contribution())
    }

    @Test
    fun `should handle multiline instruction`() {
        // Arrange
        val multiline = """
            Step 1: Read the code
            Step 2: Analyze it
            Step 3: Provide feedback
        """.trimIndent()

        // Act
        val instruction = Instruction(multiline)

        // Assert
        assertEquals(multiline, instruction.instruction)
        assertTrue(instruction.contribution().contains("Step 1"))
        assertTrue(instruction.contribution().contains("Step 2"))
        assertTrue(instruction.contribution().contains("Step 3"))
    }

    @Test
    fun `should implement PromptContributor interface`() {
        // Arrange & Act
        val instruction = Instruction("Test instruction")

        // Assert
        assertTrue(instruction is com.embabel.common.ai.prompt.PromptContributor)
    }

    @Test
    fun `should handle long instruction text`() {
        // Arrange
        val longText = "This is a very long instruction that contains many words and provides detailed guidance on how to complete a complex task with multiple steps and considerations."

        // Act
        val instruction = Instruction(longText)

        // Assert
        assertEquals(longText, instruction.instruction)
        assertEquals(longText, instruction.contribution())
    }

    @Test
    fun `should handle special characters in instruction`() {
        // Arrange
        val specialChars = "Use @annotations, #tags, \$variables, and 100% accuracy!"

        // Act
        val instruction = Instruction(specialChars)

        // Assert
        assertEquals(specialChars, instruction.instruction)
        assertEquals(specialChars, instruction.contribution())
    }
}
