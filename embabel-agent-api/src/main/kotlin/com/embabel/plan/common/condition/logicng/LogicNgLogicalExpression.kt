package com.embabel.plan.common.condition.logicng

import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.ConditionWorldState
import com.embabel.plan.common.condition.EffectSpec
import com.embabel.plan.common.condition.LogicalExpression
import org.logicng.formulas.Formula
import org.logicng.formulas.FormulaFactory
import org.logicng.io.parsers.PropositionalParser

/**
 * A specification of a logical formula over condition names.
 *
 * Supports complex formulas like:
 * - "A -> B & !C" (if A then B and not C)
 * - "A | B" (A or B)
 * - "A <=> B" (A if and only if B)
 * - "A & (B | C)" (A and either B or C)
 *
 * Example usage:
 * ```
 * val formula = FormulaSpec.parse("hasDog -> needsWalk & !tired")
 * val satisfied = formula.evaluate(worldState)
 * ```
 */
data class LogicNgLogicalExpression(
    val formula: Formula,
    val formulaFactory: FormulaFactory = FormulaFactory(),
) : LogicalExpression {
    /**
     * The condition names referenced in this formula
     */
    val variables: Set<String> by lazy {
        formula.variables().map { it.name() }.toSet()
    }

    override fun evaluate(worldState: ConditionWorldState): ConditionDetermination {
        return FormulaEvaluator.evaluate(formula, worldState, formulaFactory)
    }

    /**
     * Get a human-readable string representation of the formula
     */
    override fun toString(): String = formula.toString()

    companion object {
        /**
         * Parse a formula from a string.
         *
         * Supported operators:
         * - & (and)
         * - | (or)
         * - ~ or ! (not)
         * - -> or => (implication)
         * - <-> or <=> (equivalence)
         * - Parentheses for grouping
         *
         * Variable names can be any identifier, including "it:Dog", "hasDog", etc.
         */
        fun parse(
            formulaString: String,
            formulaFactory: FormulaFactory = FormulaFactory(),
        ): LogicNgLogicalExpression {
            val parser = PropositionalParser(formulaFactory)
            val formula = parser.parse(formulaString)
            return LogicNgLogicalExpression(formula, formulaFactory)
        }

        /**
         * Create a simple conjunction (AND) from a map of conditions.
         * This provides backward compatibility with the existing EffectSpec format.
         */
        fun fromEffectSpec(
            effectSpec: EffectSpec,
            formulaFactory: FormulaFactory = FormulaFactory(),
        ): LogicNgLogicalExpression {
            if (effectSpec.isEmpty()) {
                return LogicNgLogicalExpression(formulaFactory.verum(), formulaFactory)
            }

            val literals = effectSpec.entries.map { (name, determination) ->
                val variable = formulaFactory.variable(name)
                when (determination) {
                    ConditionDetermination.TRUE -> variable
                    ConditionDetermination.FALSE -> formulaFactory.not(variable)
                    ConditionDetermination.UNKNOWN -> throw IllegalArgumentException(
                        "Cannot convert UNKNOWN determination to formula for condition: $name"
                    )
                }
            }

            return LogicNgLogicalExpression(formulaFactory.and(literals), formulaFactory)
        }
    }
}