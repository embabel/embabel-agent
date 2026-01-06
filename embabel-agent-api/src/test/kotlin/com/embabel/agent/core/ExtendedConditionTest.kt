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
package com.embabel.agent.core

import com.embabel.agent.api.common.OperationContext
import com.embabel.common.core.types.ZeroToOne
import com.embabel.plan.common.condition.ConditionDetermination
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * Extended tests for logical operators on [Condition] implementations.
 *
 * These tests focus on the logical operator overloading provided by the Condition interface,
 * validating the behavior of complex condition chains and short-circuit evaluation.
 * The tests verify:
 *
 * - NOT operator (!condition) for negating condition results
 * - Bitwise inverse operator (~condition) for handling UNKNOWN states
 * - OR operator (a or b) with short-circuit evaluation
 * - AND operator (a and b) with short-circuit evaluation
 * - Complex condition chains with multiple operators
 * - Handling of UNKNOWN conditions in logical operations
 * - String representation formatting
 *
 * These tests complement the basic [ConditionTest] by focusing on
 * the extended logical behavior and short-circuiting capabilities.
 */
class ExtendedConditionTest {

    private val mockProcessContext = mock<OperationContext>()

    @Test
    fun `test condition inverse operator (not)`() {
        val condition = ComputedBooleanCondition("testCondition") { it, condition -> true }
        val inverse = !condition

        assertEquals("!testCondition", inverse.name)
        assertEquals(condition.cost, inverse.cost)

        assertEquals(ConditionDetermination.FALSE, inverse.evaluate(mockProcessContext))
    }

    @Test
    fun `test condition bitwise inverse operator (unknown)`() {
        val condition = ComputedBooleanCondition("testCondition") { it, condition -> true }
        val unknown = condition.inv()

        assertEquals("!testCondition", unknown.name)
        assertEquals(condition.cost, unknown.cost)

        assertEquals(ConditionDetermination.FALSE, unknown.evaluate(mockProcessContext))
    }

    @Test
    fun `test OR operator short-circuit evaluation`() {
        var secondEvaluated = false

        val first = ComputedBooleanCondition("first") { it, condition -> true }
        val second = ComputedBooleanCondition("second") { it, condition ->
            secondEvaluated = true
            false
        }

        val combined = first or second

        assertEquals("(first OR second)", combined.name)

        val result = combined.evaluate(mockProcessContext)

        assertEquals(ConditionDetermination.TRUE, result)
        assertFalse(secondEvaluated, "Second condition should not be evaluated due to short-circuit")
    }

    @Test
    fun `test AND operator short-circuit evaluation`() {
        var secondEvaluated = false

        val first = ComputedBooleanCondition("first") { it, condition -> false }
        val second = ComputedBooleanCondition("second") { it, condition ->
            secondEvaluated = true
            true
        }

        val combined = first and second

        assertEquals("(first AND second)", combined.name)

        val result = combined.evaluate(mockProcessContext)

        assertEquals(ConditionDetermination.FALSE, result)
        assertFalse(secondEvaluated, "Second condition should not be evaluated due to short-circuit")
    }

    @Test
    fun `test complex condition chains`() {
        val a = ComputedBooleanCondition("a") { it, condition -> true }
        val b = ComputedBooleanCondition("b") { it, condition -> false }
        val c = ComputedBooleanCondition("c") { it, condition -> true }

        // (a AND !b) OR c
        val complex = (a and !b) or c

        assertEquals(ConditionDetermination.TRUE, complex.evaluate(mockProcessContext))

        // Verify name has correct parentheses
        assertTrue(complex.name.contains("a"))
        assertTrue(complex.name.contains("b"))
        assertTrue(complex.name.contains("c"))
        assertTrue(complex.name.contains("AND"))
        assertTrue(complex.name.contains("OR"))
    }

    @Test
    fun `test condition with UNKNOWN evaluation`() {
        // Create a condition that always returns UNKNOWN using a custom implementation
        val unknownCondition = object : Condition {
            override val name = "unknown"
            override val cost: ZeroToOne = 0.0
            override fun evaluate(context: OperationContext) = ConditionDetermination.UNKNOWN
        }

        assertEquals(ConditionDetermination.UNKNOWN, unknownCondition.evaluate(mockProcessContext))

        // Test NOT with UNKNOWN
        val notUnknown = !unknownCondition
        assertEquals(ConditionDetermination.UNKNOWN, notUnknown.evaluate(mockProcessContext))

        // Test AND with UNKNOWN
        val trueCondition = ComputedBooleanCondition("true") { it, condition -> true }
        val unknownAndTrue = unknownCondition and trueCondition
        assertEquals(ConditionDetermination.UNKNOWN, unknownAndTrue.evaluate(mockProcessContext))

        // Test OR with UNKNOWN
        val falseCondition = ComputedBooleanCondition("false") { it, condition -> false }
        val unknownOrFalse = unknownCondition or falseCondition
        assertEquals(ConditionDetermination.UNKNOWN, unknownOrFalse.evaluate(mockProcessContext))
    }

    @Test
    fun `test condition infoString`() {
        val condition = ComputedBooleanCondition("testCondition", cost = 0.5) { it, condition -> true }
        val infoString = condition.infoString(null)

        assertTrue(infoString.contains("testCondition"))
        assertTrue(infoString.contains("0.5"))
    }
}
