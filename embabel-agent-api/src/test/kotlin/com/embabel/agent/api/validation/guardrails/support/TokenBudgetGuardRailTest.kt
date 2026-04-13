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
package com.embabel.agent.api.validation.guardrails.support

import com.embabel.agent.core.Blackboard
import com.embabel.common.ai.model.TokenCountEstimator
import com.embabel.common.core.validation.ValidationSeverity
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TokenBudgetGuardRailTest {

    private val blackboard = mockk<Blackboard>()

    private val charEstimator: TokenCountEstimator<String> = TokenCountEstimator { it.length }

    @Nested
    inner class ValidationTests {

        @Test
        fun `validates successfully when input is within budget`() {
            val guardRail = TokenBudgetGuardRail(charEstimator, maxTokens = 100)
            val result = guardRail.validate("short input", blackboard)
            assertTrue(result.isValid)
            assertTrue(result.errors.isEmpty())
        }

        @Test
        fun `returns violation when input exceeds budget`() {
            val guardRail = TokenBudgetGuardRail(charEstimator, maxTokens = 5)
            val result = guardRail.validate("this input is too long", blackboard)
            assertFalse(result.isValid)
            assertEquals(1, result.errors.size)
            assertEquals("TOKEN_BUDGET_EXCEEDED", result.errors[0].code)
        }
    }

    @Nested
    inner class SeverityTests {

        @Test
        fun `uses configured severity`() {
            val guardRail = TokenBudgetGuardRail(charEstimator, maxTokens = 5, severity = ValidationSeverity.CRITICAL)
            val result = guardRail.validate("this input is too long", blackboard)
            assertFalse(result.isValid)
            assertEquals(ValidationSeverity.CRITICAL, result.errors[0].severity)
        }
    }

    @Nested
    inner class BoundaryConditionTests {

        @Test
        fun `validates at exact boundary`() {
            val guardRail = TokenBudgetGuardRail(charEstimator, maxTokens = 5)
            val result = guardRail.validate("hello", blackboard) // length == 5 == maxTokens
            assertTrue(result.isValid)
            assertTrue(result.errors.isEmpty())
        }
    }
}
