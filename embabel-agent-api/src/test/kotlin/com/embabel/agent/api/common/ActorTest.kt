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

import com.embabel.agent.prompt.persona.Instruction
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActorTest {

    @Test
    fun `should create actor with persona llm and tool groups`() {
        // Arrange
        val persona = Instruction("Test instruction")
        val llm = LlmOptions("test-model")
        val toolGroups = setOf("group1", "group2")

        // Act
        val actor = Actor(persona, llm, toolGroups)

        // Assert
        assertEquals(persona, actor.persona)
        assertEquals(llm, actor.llm)
        assertEquals(toolGroups, actor.toolGroups)
    }

    @Test
    fun `should create actor with default empty tool groups`() {
        // Arrange
        val persona = Instruction("Test instruction")
        val llm = LlmOptions("test-model")

        // Act
        val actor = Actor(persona, llm)

        // Assert
        assertEquals(persona, actor.persona)
        assertEquals(llm, actor.llm)
        assertTrue(actor.toolGroups.isEmpty())
    }

    @Test
    fun `should create actor from instruction string`() {
        // Arrange
        val instruction = "You are a helpful assistant"
        val llm = LlmOptions("test-model")
        val toolGroups = setOf("tools")

        // Act
        val actor = Actor<Instruction>(instruction, llm, toolGroups)

        // Assert
        assertEquals(llm, actor.llm)
        assertEquals(toolGroups, actor.toolGroups)
    }

    @Test
    fun `should create actor from instruction string with default tool groups`() {
        // Arrange
        val instruction = "Test instruction"
        val llm = LlmOptions("test-model")

        // Act
        val actor = Actor<Instruction>(instruction, llm)

        // Assert
        assertEquals(llm, actor.llm)
        assertTrue(actor.toolGroups.isEmpty())
    }

    @Test
    fun `should have meaningful toString representation`() {
        // Arrange
        val persona = Instruction("Test persona")
        val llm = LlmOptions("test-model")
        val toolGroups = setOf("group1", "group2")
        val actor = Actor(persona, llm, toolGroups)

        // Act
        val string = actor.toString()

        // Assert
        assertTrue(string.contains("Actor"))
        assertTrue(string.contains("persona"))
        assertTrue(string.contains("llm"))
        assertTrue(string.contains("toolGroups"))
    }
}
