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
package com.embabel.plan.common.condition.prolog

import com.embabel.plan.common.condition.ConditionDetermination
import com.embabel.plan.common.condition.LogicalExpression

/**
 * Wraps a PrologLogicalExpression to automatically inject blackboard objects as Prolog facts.
 *
 * This wrapper intercepts the evaluate() call and:
 * 1. Gets all @PrologFact annotated objects from the provided collection
 * 2. Converts them to Prolog facts
 * 3. Asserts them into the engine before evaluation
 *
 * Usage:
 * ```
 * val expression = PrologLogicalExpression("elephant_age(E, Age), Age > 20", engine)
 * val wrappedExpression = PrologBlackboardHelper.wrapWithObjects(expression, blackboardObjects)
 * val result = wrappedExpression.evaluate(determineCondition)
 * ```
 */
object PrologBlackboardHelper {

    /**
     * Wrap a PrologLogicalExpression to automatically inject objects as facts.
     */
    fun wrapWithObjects(
        expression: PrologLogicalExpression,
        objects: Collection<Any>
    ): LogicalExpression {
        return BlackboardAwarePrologExpression(expression, objects)
    }

    private class BlackboardAwarePrologExpression(
        private val expression: PrologLogicalExpression,
        private val blackboardObjects: Collection<Any>
    ) : LogicalExpression {

        override fun evaluate(determineCondition: (String) -> ConditionDetermination): ConditionDetermination {
            // Inject all blackboard objects as facts before evaluation
            val expressionWithFacts = expression.withObjects(*blackboardObjects.toTypedArray())
            return expressionWithFacts.evaluate(determineCondition)
        }

        override fun toString(): String = "BlackboardAware($expression)"
    }
}
