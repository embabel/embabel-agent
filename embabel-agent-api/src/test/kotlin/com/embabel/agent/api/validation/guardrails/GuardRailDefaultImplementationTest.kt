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
package com.embabel.agent.api.validation.guardrails

import com.embabel.agent.api.common.MultimodalContent
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.validation.ValidationResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests specifically for the default implementations in GuardRail interfaces.
 * These tests target the uncovered lines in interface default methods.
 */
class GuardRailDefaultImplementationTest {

    private val blackboard = InMemoryBlackboard()

    @Test
    fun `UserInputGuardRail default validate with List should combine messages and delegate to base validate`() {
        // Create guard that only implements the base validate method - uses default implementations
        val guard = object : UserInputGuardRail {
            override val name = "DefaultTestGuard"
            override val description = "Tests default implementations"
            
            var lastValidatedInput: String? = null
            
            override fun validate(input: String, blackboard: com.embabel.agent.core.Blackboard): ValidationResult {
                lastValidatedInput = input
                return ValidationResult.VALID
            }
        }

        // Test the default validate(List<UserMessage>) implementation
        val messages = listOf(
            UserMessage("First message"),
            UserMessage("Second message")
        )
        
        val result = guard.validate(messages, blackboard)
        
        assertEquals(ValidationResult.VALID, result)
        assertEquals("First message\nSecond message", guard.lastValidatedInput)
    }

    @Test
    fun `UserInputGuardRail default validate with MultimodalContent should extract text and delegate`() {
        val guard = object : UserInputGuardRail {
            override val name = "DefaultTestGuard"
            override val description = "Tests default implementations"
            
            var lastValidatedInput: String? = null
            
            override fun validate(input: String, blackboard: com.embabel.agent.core.Blackboard): ValidationResult {
                lastValidatedInput = input
                return ValidationResult.VALID
            }
        }

        // Test the default validate(MultimodalContent) implementation  
        val content = MultimodalContent(text = "Multimodal text")
        
        val result = guard.validate(content, blackboard)
        
        assertEquals(ValidationResult.VALID, result)
        assertEquals("Multimodal text", guard.lastValidatedInput)
    }

    @Test
    fun `AssistantMessageGuardRail default validate with AssistantMessage should extract content and delegate`() {
        val guard = object : AssistantMessageGuardRail {
            override val name = "DefaultAssistantTestGuard"
            override val description = "Tests default implementations"
            
            var lastValidatedInput: String? = null
            
            override fun validate(input: String, blackboard: com.embabel.agent.core.Blackboard): ValidationResult {
                lastValidatedInput = input
                return ValidationResult.VALID
            }
            
            override fun validate(response: ThinkingResponse<*>, blackboard: com.embabel.agent.core.Blackboard): ValidationResult {
                // Required implementation, but we're testing the AssistantMessage default method
                return ValidationResult.VALID
            }
        }

        // Test the default validate(AssistantMessage) implementation
        val message = AssistantMessage("Assistant response content")
        
        val result = guard.validate(message, blackboard)
        
        assertEquals(ValidationResult.VALID, result)
        assertEquals("Assistant response content", guard.lastValidatedInput)
    }
}