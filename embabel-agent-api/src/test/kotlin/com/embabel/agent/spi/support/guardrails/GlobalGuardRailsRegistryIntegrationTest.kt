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
package com.embabel.agent.spi.support.guardrails

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.validation.ValidationError
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Spring Boot integration test for GlobalGuardRailsRegistry.
 * Verifies Spring integration and guardrail merging with properties.
 */
@SpringBootTest(classes = [GlobalGuardRailsRegistryIntegrationTest.TestConfiguration::class])
@ActiveProfiles("guardrails")
@TestPropertySource(
    properties = [
        "embabel.agent.guardrails.user-input=com.embabel.agent.spi.support.guardrails.GlobalUserGuardRail",
        "embabel.agent.guardrails.assistant-message=com.embabel.agent.spi.support.guardrails.GlobalAssistantGuardRail"
    ]
)
class GlobalGuardRailsRegistryIntegrationTest {

    @Autowired
    private lateinit var registry: GlobalGuardRailsRegistry

    @BeforeEach
    fun resetInvocationCounts() {
        // Reset singleton guardrail invocation counts before each test
        val globalUserGuard = GlobalGuardRailsRegistry.getUserInputGuardRails().firstOrNull() as? GlobalUserGuardRail
        globalUserGuard?.invocationCount = 0
        val globalAssistantGuard = GlobalGuardRailsRegistry.getAssistantMessageGuardRails().firstOrNull() as? GlobalAssistantGuardRail
        globalAssistantGuard?.invocationCount = 0
    }

    @Nested
    inner class RegistrySpringIntegrationTests {

        @Test
        fun `should autowire registry bean`() {
            assertNotNull(registry)
        }

        @Test
        fun `should load user input guardrails from properties`() {
            val guards = GlobalGuardRailsRegistry.getUserInputGuardRails()

            assertEquals(1, guards.size)
            assertEquals("GlobalUserGuardRail", guards.first().name)
        }

        @Test
        fun `should load assistant message guardrails from properties`() {
            val guards = GlobalGuardRailsRegistry.getAssistantMessageGuardRails()

            assertEquals(1, guards.size)
            assertEquals("GlobalAssistantGuardRail", guards.first().name)
        }

        @Test
        fun `should access registry via singleton pattern`() {
            val instance = GlobalGuardRailsRegistry.get()

            assertNotNull(instance)
            assertSame(registry, instance)
        }

        @Test
        fun `should return same guardrail instances across multiple accesses`() {
            val guards1 = GlobalGuardRailsRegistry.getUserInputGuardRails()
            val guards2 = GlobalGuardRailsRegistry.getUserInputGuardRails()

            assertSame(guards1, guards2)
        }
    }

    @Nested
    inner class GuardRailMergingIntegrationTests {

        @Test
        fun `should merge global and interaction-specific guardrails`() {
            val interactionGuard = InteractionUserGuardRail()
            val interaction = LlmInteraction(
                id = InteractionId("test"),
                guardRails = listOf(interactionGuard)
            )

            validateUserInput("test input", interaction, InMemoryBlackboard())

            val globalGuard = GlobalGuardRailsRegistry.getUserInputGuardRails().first() as GlobalUserGuardRail
            assertEquals(1, globalGuard.invocationCount, "Global guardrail should be invoked")
            assertEquals(1, interactionGuard.invocationCount, "Interaction guardrail should be invoked")
        }

        @Test
        fun `should use only global guardrails when no interaction guardrails`() {
            val interaction = LlmInteraction(
                id = InteractionId("test"),
                guardRails = emptyList()
            )

            validateUserInput("test input", interaction, InMemoryBlackboard())

            val globalGuard = GlobalGuardRailsRegistry.getUserInputGuardRails().first() as GlobalUserGuardRail
            assertEquals(1, globalGuard.invocationCount)
        }

        @Test
        fun `should remove duplicate guardrails by name`() {
            val duplicateGuard = DuplicateNameGuardRail()
            val interaction = LlmInteraction(
                id = InteractionId("test"),
                guardRails = listOf(duplicateGuard)
            )

            validateUserInput("test input", interaction, InMemoryBlackboard())

            val globalGuard = GlobalGuardRailsRegistry.getUserInputGuardRails().first() as GlobalUserGuardRail
            assertEquals(1, globalGuard.invocationCount, "Global guardrail should be invoked")
            assertEquals(0, duplicateGuard.invocationCount, "Duplicate should be filtered out")
        }

        @Test
        fun `should throw exception for critical violations`() {
            val criticalGuard = CriticalUserGuardRail()
            val interaction = LlmInteraction(
                id = InteractionId("test"),
                guardRails = listOf(criticalGuard)
            )

            assertThrows(GuardRailViolationException::class.java) {
                validateUserInput("test input", interaction, InMemoryBlackboard())
            }
        }

        @Test
        fun `should merge assistant message guardrails`() {
            val interaction = LlmInteraction(
                id = InteractionId("test"),
                guardRails = emptyList()
            )

            validateAssistantResponse("test response", interaction, InMemoryBlackboard())

            val globalGuard =
                GlobalGuardRailsRegistry.getAssistantMessageGuardRails().first() as GlobalAssistantGuardRail
            assertEquals(1, globalGuard.invocationCount)
        }

        @Test
        fun `should work without blackboard`() {
            val interaction = LlmInteraction(
                id = InteractionId("test"),
                guardRails = emptyList()
            )

            assertDoesNotThrow {
                validateUserInput("test input", interaction, null)
            }

            val globalGuard = GlobalGuardRailsRegistry.getUserInputGuardRails().first() as GlobalUserGuardRail
            assertEquals(1, globalGuard.invocationCount)
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan(basePackages = ["com.embabel.agent.spi.support.guardrails"])
    class TestConfiguration
}

/**
 * Global test guardrail for user input.
 */
class GlobalUserGuardRail : UserInputGuardRail {
    var invocationCount = 0

    override val name: String = "GlobalUserGuardRail"
    override val description: String = "Global user guardrail"
    override fun validate(input: String, blackboard: Blackboard): ValidationResult {
        invocationCount++
        return ValidationResult(true, emptyList())
    }
}

/**
 * Global test guardrail for assistant messages.
 */
class GlobalAssistantGuardRail : AssistantMessageGuardRail {
    var invocationCount = 0

    override val name: String = "GlobalAssistantGuardRail"
    override val description: String = "Global assistant guardrail"
    override fun validate(input: String, blackboard: Blackboard): ValidationResult {
        invocationCount++
        return ValidationResult(true, emptyList())
    }

    override fun validate(response: ThinkingResponse<*>, blackboard: Blackboard): ValidationResult {
        invocationCount++
        return ValidationResult(true, emptyList())
    }
}

/**
 * Test guardrail for interaction-specific usage.
 */
class InteractionUserGuardRail : UserInputGuardRail {
    var invocationCount = 0

    override val name: String = "InteractionUserGuardRail"
    override val description: String = "Interaction user guardrail"
    override fun validate(input: String, blackboard: Blackboard): ValidationResult {
        invocationCount++
        return ValidationResult(true, emptyList())
    }
}

/**
 * Test guardrail with duplicate name to test deduplication.
 */
class DuplicateNameGuardRail : UserInputGuardRail {
    var invocationCount = 0

    override val name: String = "GlobalUserGuardRail" // Same name as GlobalUserGuardRail
    override val description: String = "Duplicate name guardrail"
    override fun validate(input: String, blackboard: Blackboard): ValidationResult {
        invocationCount++
        return ValidationResult(true, emptyList())
    }
}

/**
 * Test guardrail that returns critical validation error.
 */
class CriticalUserGuardRail : UserInputGuardRail {
    override val name: String = "CriticalUserGuardRail"
    override val description: String = "Critical severity guardrail"
    override fun validate(input: String, blackboard: Blackboard): ValidationResult {
        return ValidationResult(
            false,
            listOf(ValidationError("critical-error", "Critical violation", ValidationSeverity.CRITICAL))
        )
    }
}
