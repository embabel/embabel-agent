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

import com.embabel.agent.core.Blackboard
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.validation.ValidationResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for GuardRailConfiguration direct instance management.
 */
class GuardRailConfigurationTest {

    @Test
    fun `hasGuards should determine when validation is required in proxy factory`() {
        val emptyConfig = GuardRailConfiguration()
        val configWithGuards = GuardRailConfiguration(guards = listOf(createTestUserInputGuard()))

        assertFalse(emptyConfig.hasGuards())
        assertTrue(configWithGuards.hasGuards())
    }

    @Test
    fun `withGuardRails should preserve existing guards when building guard chains`() {
        val baseConfig = GuardRailConfiguration(guards = listOf(createTestUserInputGuard()))

        val enhancedConfig = baseConfig.withGuardRails(createTestAssistantGuard())

        assertEquals(1, baseConfig.guards.size)
        assertEquals(2, enhancedConfig.guards.size)

        assertEquals("UserInputValidator", enhancedConfig.guards[0].name)
        assertEquals("AssistantMessageValidator", enhancedConfig.guards[1].name)
    }

    @Test
    fun `fluent configuration should support complex guardrail setup scenarios`() {
        val productionConfig = GuardRailConfiguration.NONE
            .withGuardRails(createTestUserInputGuard())
            .withGuardRails(createTestAssistantGuard())

        assertEquals(2, productionConfig.guards.size)
        assertTrue(productionConfig.hasGuards())

        assertEquals("UserInputValidator", productionConfig.guards[0].name)
        assertEquals("AssistantMessageValidator", productionConfig.guards[1].name)
    }

    @Test
    fun `withGuardRails should handle multiple guard types correctly`() {
        val mixedConfig = GuardRailConfiguration()
            .withGuardRails(createTestUserInputGuard(), createTestAssistantGuard())

        assertEquals(2, mixedConfig.guards.size)
        assertTrue(mixedConfig.hasGuards())

        val guardNames = mixedConfig.guards.map { it.name }.toSet()
        assertTrue(guardNames.contains("UserInputValidator"))
        assertTrue(guardNames.contains("AssistantMessageValidator"))
    }

    private fun createTestUserInputGuard() = object : UserInputGuardRail {
        override val name = "UserInputValidator"
        override val description = "User input validation guard"
        override fun validate(input: String, blackboard: Blackboard) = ValidationResult.VALID
        override fun validate(userMessages: List<UserMessage>, blackboard: Blackboard) = ValidationResult.VALID
    }

    private fun createTestAssistantGuard() = object : AssistantMessageGuardRail {
        override val name = "AssistantMessageValidator"
        override val description = "Assistant message validation guard"
        override fun validate(input: String, blackboard: Blackboard) = ValidationResult.VALID
        override fun validate(message: AssistantMessage, blackboard: Blackboard) = ValidationResult.VALID
        override fun validate(response: ThinkingResponse<*>, blackboard: Blackboard) = ValidationResult.VALID
    }
}
