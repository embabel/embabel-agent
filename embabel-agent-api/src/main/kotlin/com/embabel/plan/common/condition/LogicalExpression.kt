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

/**
 * Represents a parsed logical expression. May be backed by different implementations.
 */
interface LogicalExpression {

    /**
     * Evaluate this formula using a condition determiner using three-valued logic.
     *
     * This allows the expression to work with [WorldStateDeterminer.determineCondition]
     * without requiring the full [ConditionWorldState].
     *
     * Returns:
     * - TRUE if the formula evaluates to true given known conditions
     * - FALSE if the formula evaluates to false given known conditions
     * - UNKNOWN if the formula cannot be determined (contains unknown conditions)
     *
     * @param determineCondition Function that determines the value of a condition by name
     */
    fun evaluate(determineCondition: (String) -> ConditionDetermination): ConditionDetermination

    /**
     * Convenience method to evaluate against a [ConditionWorldState].
     */
    fun evaluate(worldState: ConditionWorldState): ConditionDetermination =
        evaluate { condition -> worldState.state[condition] ?: ConditionDetermination.FALSE }
}
