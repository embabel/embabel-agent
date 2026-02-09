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
package com.embabel.agent.core.expression

import com.embabel.agent.core.Blackboard
import com.embabel.plan.common.condition.ConditionDetermination

/**
 * Represents a parsed logical expression. May be backed by different implementations.
 */
interface LogicalExpression {

    /**
     * Evaluate this formula using a condition determiner using three-valued logic.
     *
     * This allows the expression to work with [com.embabel.plan.common.condition.WorldStateDeterminer.determineCondition]
     * without requiring the full [com.embabel.plan.common.condition.ConditionWorldState].
     *
     * Returns:
     * - TRUE if the formula evaluates to true given known conditions
     * - FALSE if the formula evaluates to false given known conditions
     * - UNKNOWN if the formula cannot be determined (contains unknown conditions)
     *
     * @param blackboard blackboard to use for condition evaluation
     */
    fun evaluate(blackboard: Blackboard): ConditionDetermination

}
