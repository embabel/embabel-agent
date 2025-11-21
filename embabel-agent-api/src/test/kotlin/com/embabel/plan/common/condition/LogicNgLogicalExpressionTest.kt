/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.plan.common.condition

import com.embabel.plan.common.condition.logicng.LogicNgLogicalExpression
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Proof-of-concept test demonstrating LogicNG integration for formula-based planning.
 *
 * This test shows how complex logical formulas can be evaluated against world states
 * using three-valued logic (TRUE, FALSE, UNKNOWN).
 */
@DisplayName("FormulaSpec - Formula-based Planning with LogicNG")
class LogicNgLogicalExpressionTest {

    @Test
    fun `simple conjunction - all conditions true`() {
        // Given: World state where hasDog=true and needsWalk=true
        val worldState = ConditionWorldState(
            state = mapOf(
                "hasDog" to ConditionDetermination.TRUE,
                "needsWalk" to ConditionDetermination.TRUE
            )
        )

        // When: Evaluate formula "hasDog & needsWalk"
        val formula = LogicNgLogicalExpression.parse("hasDog & needsWalk")

        // Then: Formula should be TRUE
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.TRUE)
    }

    @Test
    fun `simple conjunction - one condition false`() {
        // Given: World state where hasDog=true but needsWalk=false
        val worldState = ConditionWorldState(
            state = mapOf(
                "hasDog" to ConditionDetermination.TRUE,
                "needsWalk" to ConditionDetermination.FALSE
            )
        )

        // When: Evaluate formula "hasDog & needsWalk"
        val formula = LogicNgLogicalExpression.parse("hasDog & needsWalk")

        // Then: Formula should be FALSE
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.FALSE)
    }

    @Test
    fun `implication - antecedent true and consequent true`() {
        // Given: World state where hasDog=true and needsWalk=true
        val worldState = ConditionWorldState(
            state = mapOf(
                "hasDog" to ConditionDetermination.TRUE,
                "needsWalk" to ConditionDetermination.TRUE
            )
        )

        // When: Evaluate formula "hasDog => needsWalk" (if hasDog then needsWalk)
        val formula = LogicNgLogicalExpression.parse("hasDog => needsWalk")

        // Then: Formula should be TRUE
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.TRUE)
    }

    @Test
    fun `implication - antecedent true and consequent false`() {
        // Given: World state where hasDog=true but needsWalk=false
        val worldState = ConditionWorldState(
            state = mapOf(
                "hasDog" to ConditionDetermination.TRUE,
                "needsWalk" to ConditionDetermination.FALSE
            )
        )

        // When: Evaluate formula "hasDog => needsWalk"
        val formula = LogicNgLogicalExpression.parse("hasDog => needsWalk")

        // Then: Formula should be FALSE (implication violated)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.FALSE)
    }

    @Test
    fun `implication - antecedent false`() {
        // Given: World state where hasDog=false
        val worldState = ConditionWorldState(
            state = mapOf(
                "hasDog" to ConditionDetermination.FALSE,
                "needsWalk" to ConditionDetermination.FALSE
            )
        )

        // When: Evaluate formula "hasDog => needsWalk"
        val formula = LogicNgLogicalExpression.parse("hasDog => needsWalk")

        // Then: Formula should be TRUE (implication is vacuously true when antecedent is false)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.TRUE)
    }

    @Test
    fun `complex formula - implication with conjunction and negation`() {
        // Given: World state for the user's example: "A -> B && !C"
        val worldState = ConditionWorldState(
            state = mapOf(
                "A" to ConditionDetermination.TRUE,
                "B" to ConditionDetermination.TRUE,
                "C" to ConditionDetermination.FALSE
            )
        )

        // When: Evaluate formula "A => (B & ~C)"
        val formula = LogicNgLogicalExpression.parse("A => (B & ~C)")

        // Then: Formula should be TRUE (A is true, and both B is true and C is false)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.TRUE)
    }

    @Test
    fun `complex formula - violation of implication`() {
        // Given: World state where A=true, B=true, but C=true (violates !C)
        val worldState = ConditionWorldState(
            state = mapOf(
                "A" to ConditionDetermination.TRUE,
                "B" to ConditionDetermination.TRUE,
                "C" to ConditionDetermination.TRUE
            )
        )

        // When: Evaluate formula "A => (B & ~C)"
        val formula = LogicNgLogicalExpression.parse("A => (B & ~C)")

        // Then: Formula should be FALSE (implication violated because C is true)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.FALSE)
    }

    @Test
    fun `disjunction - at least one true`() {
        // Given: World state where hasDog=true, hasCat=false
        val worldState = ConditionWorldState(
            state = mapOf(
                "hasDog" to ConditionDetermination.TRUE,
                "hasCat" to ConditionDetermination.FALSE
            )
        )

        // When: Evaluate formula "hasDog | hasCat"
        val formula = LogicNgLogicalExpression.parse("hasDog | hasCat")

        // Then: Formula should be TRUE
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.TRUE)
    }

    @Test
    fun `negation - simple not`() {
        // Given: World state where tired=true
        val worldState = ConditionWorldState(
            state = mapOf(
                "tired" to ConditionDetermination.TRUE
            )
        )

        // When: Evaluate formula "~tired"
        val formula = LogicNgLogicalExpression.parse("~tired")

        // Then: Formula should be FALSE
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.FALSE)
    }

    @Test
    fun `three-valued logic - unknown condition in conjunction`() {
        // Given: World state where A=true but B=unknown
        val worldState = ConditionWorldState(
            state = mapOf(
                "A" to ConditionDetermination.TRUE,
                "B" to ConditionDetermination.UNKNOWN
            )
        )

        // When: Evaluate formula "A & B"
        val formula = LogicNgLogicalExpression.parse("A & B")

        // Then: Formula should be UNKNOWN (depends on B)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.UNKNOWN)
    }

    @Test
    fun `three-valued logic - false dominates unknown in conjunction`() {
        // Given: World state where A=false and B=unknown
        val worldState = ConditionWorldState(
            state = mapOf(
                "A" to ConditionDetermination.FALSE,
                "B" to ConditionDetermination.UNKNOWN
            )
        )

        // When: Evaluate formula "A & B"
        val formula = LogicNgLogicalExpression.parse("A & B")

        // Then: Formula should be FALSE (false dominates in AND)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.FALSE)
    }

    @Test
    fun `three-valued logic - true dominates unknown in disjunction`() {
        // Given: World state where A=true and B=unknown
        val worldState = ConditionWorldState(
            state = mapOf(
                "A" to ConditionDetermination.TRUE,
                "B" to ConditionDetermination.UNKNOWN
            )
        )

        // When: Evaluate formula "A | B"
        val formula = LogicNgLogicalExpression.parse("A | B")

        // Then: Formula should be TRUE (true dominates in OR)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.TRUE)
    }

    @Test
    fun `three-valued logic - unknown in disjunction with false`() {
        // Given: World state where A=false and B=unknown
        val worldState = ConditionWorldState(
            state = mapOf(
                "A" to ConditionDetermination.FALSE,
                "B" to ConditionDetermination.UNKNOWN
            )
        )

        // When: Evaluate formula "A | B"
        val formula = LogicNgLogicalExpression.parse("A | B")

        // Then: Formula should be UNKNOWN (depends on B)
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.UNKNOWN)
    }

    @Test
    fun `backward compatibility - convert EffectSpec to FormulaSpec`() {
        // Given: Traditional EffectSpec (Map format)
        val effectSpec: EffectSpec = mapOf(
            "hasDog" to ConditionDetermination.TRUE,
            "needsWalk" to ConditionDetermination.TRUE,
            "tired" to ConditionDetermination.FALSE
        )

        // When: Convert to FormulaSpec
        val formula = LogicNgLogicalExpression.fromEffectSpec(effectSpec)

        // And: Evaluate against matching world state
        val worldState = ConditionWorldState(state = effectSpec)

        // Then: Should evaluate to TRUE
        assertThat(formula.evaluate(worldState)).isEqualTo(ConditionDetermination.TRUE)
    }

    @Test
    fun `extract variables from formula`() {
        // Given: Formula with multiple variables
        val formula = LogicNgLogicalExpression.parse("(A => B) & ~C")

        // When: Extract variables
        val variables = formula.variables

        // Then: Should contain all referenced variables
        assertThat(variables).containsExactlyInAnyOrder("A", "B", "C")
    }

    @Test
    fun `formula toString returns readable representation`() {
        // Given: Complex formula
        val formula = LogicNgLogicalExpression.parse("A => (B & ~C)")

        // When: Convert to string
        val str = formula.toString()

        // Then: Should contain the formula structure
        assertThat(str).isNotEmpty()
        println("Formula representation: $str")
    }
}
