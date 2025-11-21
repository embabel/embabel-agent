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
package com.embabel.plan.common.condition.logicng

import com.embabel.plan.common.condition.ConditionDetermination
import org.logicng.datastructures.Assignment
import org.logicng.formulas.Formula
import org.logicng.formulas.FormulaFactory
import org.logicng.formulas.Literal

/**
 * Evaluates LogicNG formulas using three-valued logic (TRUE, FALSE, UNKNOWN)
 * based on Kleene's logic.
 *
 * Three-valued logic rules:
 * - AND: false if any operand is false, unknown if any operand is unknown (and none are false), true otherwise
 * - OR: true if any operand is true, unknown if any operand is unknown (and none are true), false otherwise
 * - NOT: inverts true/false, preserves unknown
 * - IMPLIES (A -> B): equivalent to (~A | B)
 */
object FormulaEvaluator {

    /**
     * Evaluate a formula using a condition determiner function using three-valued logic.
     *
     * @param formula The LogicNG formula to evaluate
     * @param determineCondition Function that determines the value of a condition by name
     * @param formulaFactory The formula factory used to create the formula
     */
    fun evaluate(
        formula: Formula,
        determineCondition: (String) -> ConditionDetermination,
        formulaFactory: FormulaFactory = FormulaFactory(),
    ): ConditionDetermination {
        val variables = formula.variables()

        // Collect known assignments and unknown variables
        val knownAssignments = mutableListOf<Literal>()
        val unknownVariables = mutableSetOf<String>()

        for (variable in variables) {
            val varName = variable.name()
            when (val determination = determineCondition(varName)) {
                ConditionDetermination.TRUE -> knownAssignments.add(formulaFactory.literal(varName, true))
                ConditionDetermination.FALSE -> knownAssignments.add(formulaFactory.literal(varName, false))
                ConditionDetermination.UNKNOWN -> unknownVariables.add(varName)
            }
        }

        // If no unknowns, evaluate directly
        if (unknownVariables.isEmpty()) {
            val assignment = Assignment(knownAssignments)
            return if (formula.evaluate(assignment)) {
                ConditionDetermination.TRUE
            } else {
                ConditionDetermination.FALSE
            }
        }

        // With unknowns, check if the formula is determined regardless of unknown values
        // Try both possibilities for each unknown and see if result is consistent
        return evaluateWithUnknowns(formula, knownAssignments, unknownVariables.toList(), formulaFactory)
    }

    /**
     * Evaluate formula with unknown variables by checking all possible assignments.
     * If all assignments yield the same result, return that result. Otherwise return UNKNOWN.
     */
    private fun evaluateWithUnknowns(
        formula: Formula,
        knownAssignments: List<Literal>,
        unknownVariables: List<String>,
        formulaFactory: FormulaFactory,
    ): ConditionDetermination {
        if (unknownVariables.isEmpty()) {
            val assignment = Assignment(knownAssignments)
            return if (formula.evaluate(assignment)) {
                ConditionDetermination.TRUE
            } else {
                ConditionDetermination.FALSE
            }
        }

        // For performance, limit exhaustive search to small number of unknowns
        if (unknownVariables.size > 10) {
            // For large numbers of unknowns, we could use BDDs or SAT solvers more efficiently
            // For now, return UNKNOWN
            return ConditionDetermination.UNKNOWN
        }

        // Try all possible assignments of unknown variables
        val numCombinations = 1 shl unknownVariables.size // 2^n
        var firstResult: Boolean? = null

        for (i in 0 until numCombinations) {
            val assignments = knownAssignments.toMutableList()

            // Generate assignment based on bits in i
            for (j in unknownVariables.indices) {
                val varName = unknownVariables[j]
                val bitSet = (i and (1 shl j)) != 0
                assignments.add(formulaFactory.literal(varName, bitSet))
            }

            val assignment = Assignment(assignments)
            val result = formula.evaluate(assignment)

            if (firstResult == null) {
                firstResult = result
            } else if (firstResult != result) {
                // Results differ based on unknown values
                return ConditionDetermination.UNKNOWN
            }
        }

        // All assignments yielded the same result
        return if (firstResult == true) {
            ConditionDetermination.TRUE
        } else {
            ConditionDetermination.FALSE
        }
    }
}
