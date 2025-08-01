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
package com.embabel.agent.core

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.experimental.primitive.PromptCondition
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.indent
import com.embabel.plan.goap.ConditionDetermination
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * A Condition is a named, well known predicate that can be evaluated
 * and reused across multiple Actions.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.DEDUCTION,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = PromptCondition::class),
)
interface Condition : Operation, HasInfoString {

    /**
     * Cost of evaluating the condition. 0 is cheap, 1 is expensive.
     * Helps in planning.
     */
    val cost: ZeroToOne

    /**
     * Evaluate the condition in the context of the process.
     * This may be expensive, so the cost is provided.
     * The infrastructure will attempt to call this function infrequently on expensive conditions,
     * so there's no urgency to optimize here.
     */
    fun evaluate(
        context: OperationContext,
    ): ConditionDetermination

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "Condition(name='$name', cost=$cost)".indent(indent)

    operator fun not(): Condition = NotCondition(this)

    operator fun inv(): Condition = UnknownCondition(this)

    infix fun or(b: Condition): Condition = OrCondition(this, b)

    infix fun and(b: Condition): Condition = AndCondition(this, b)
}

/**
 * Convenient class for a condition that evaluates to true or false.
 * @param evaluator evaluation method. Takes condition (this) for use
 * by anonymous inner classes.
 */
class ComputedBooleanCondition(
    override val name: String,
    override val cost: ZeroToOne = 0.0,
    private val evaluator: (context: OperationContext, condition: Condition) -> Boolean,
) : Condition {

    override fun evaluate(context: OperationContext): ConditionDetermination =
        ConditionDetermination(evaluator(context, this))

    override fun toString(): String = "${javaClass.simpleName}(name='$name', cost=$cost)"
}


private class NotCondition(private val condition: Condition) : Condition {
    override val name = "!${condition.name}"
    override val cost = condition.cost
    override fun evaluate(context: OperationContext) = when (condition.evaluate(context)) {
        ConditionDetermination.TRUE -> ConditionDetermination.FALSE
        ConditionDetermination.FALSE -> ConditionDetermination.TRUE
        ConditionDetermination.UNKNOWN -> ConditionDetermination.UNKNOWN
    }
}

private class UnknownCondition(private val condition: Condition) : Condition {
    override val name = "!${condition.name}"
    override val cost = condition.cost
    override fun evaluate(context: OperationContext) = when (condition.evaluate(context)) {
        ConditionDetermination.TRUE -> ConditionDetermination.FALSE
        ConditionDetermination.FALSE -> ConditionDetermination.FALSE
        ConditionDetermination.UNKNOWN -> ConditionDetermination.TRUE
    }
}

private class OrCondition(
    private val a: Condition,
    private val b: Condition,
) : Condition {
    override val name = "(${a.name} OR ${b.name})"

    // The cost is the minimum of both conditions since we can short-circuit
    // after evaluating the cheaper condition if it's TRUE
    override val cost = minOf(a.cost, b.cost)

    override fun evaluate(context: OperationContext): ConditionDetermination {
        val aResult = a.evaluate(context)
        // Short-circuit if a is TRUE
        if (aResult == ConditionDetermination.TRUE) return ConditionDetermination.TRUE

        val bResult = b.evaluate(context)
        // If either is TRUE, result is TRUE
        if (bResult == ConditionDetermination.TRUE) return ConditionDetermination.TRUE

        // If either is UNKNOWN, result is UNKNOWN
        if (aResult == ConditionDetermination.UNKNOWN || bResult == ConditionDetermination.UNKNOWN)
            return ConditionDetermination.UNKNOWN

        // Both must be FALSE
        return ConditionDetermination.FALSE
    }
}

private class AndCondition(
    private val a: Condition,
    private val b: Condition,
) : Condition {
    override val name = "(${a.name} AND ${b.name})"

    // The cost is the minimum of both conditions since we can short-circuit
    // after evaluating the cheaper condition if it's FALSE
    override val cost = minOf(a.cost, b.cost)

    override fun evaluate(context: OperationContext): ConditionDetermination {
        val aResult = a.evaluate(context)
        // Short-circuit if a is FALSE
        if (aResult == ConditionDetermination.FALSE) return ConditionDetermination.FALSE

        val bResult = b.evaluate(context)
        // If either is FALSE, result is FALSE
        if (bResult == ConditionDetermination.FALSE) return ConditionDetermination.FALSE

        // If either is UNKNOWN, result is UNKNOWN
        if (aResult == ConditionDetermination.UNKNOWN || bResult == ConditionDetermination.UNKNOWN)
            return ConditionDetermination.UNKNOWN

        // Both must be TRUE
        return ConditionDetermination.TRUE
    }
}
