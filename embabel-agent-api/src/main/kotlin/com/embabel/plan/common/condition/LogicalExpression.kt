package com.embabel.plan.common.condition

interface LogicalExpression {
    
    /**
     * Evaluate this formula against a condition world state using three-valued logic.
     *
     * Returns:
     * - TRUE if the formula evaluates to true given known conditions
     * - FALSE if the formula evaluates to false given known conditions
     * - UNKNOWN if the formula cannot be determined (contains unknown conditions)
     */
    fun evaluate(worldState: ConditionWorldState): ConditionDetermination
}