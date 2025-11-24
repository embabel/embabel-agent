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

import com.embabel.agent.core.Blackboard
import com.embabel.plan.common.condition.LogicalExpression
import com.embabel.plan.common.condition.LogicalExpressionParser

/**
 * A Prolog expression parser that automatically provides blackboard objects as Prolog facts.
 *
 * This parser wraps a standard PrologExpressionParser and ensures that when expressions
 * are evaluated, all @PrologFact annotated objects from the blackboard are automatically
 * converted to Prolog facts and asserted into the engine.
 *
 * @param baseParser The underlying Prolog expression parser
 * @param blackboardProvider Function that provides the current blackboard for fact extraction
 */
class PrologBlackboardExpressionParser(
    private val baseParser: PrologLogicalExpressionParser,
    private val blackboardProvider: () -> Blackboard,
) : LogicalExpressionParser {

    override fun parse(expression: String): LogicalExpression? {
        val baseExpression = baseParser.parse(expression)
        if (baseExpression !is PrologLogicalExpression) {
            return baseExpression
        }

        // Wrap the expression to inject blackboard objects as facts
        return PrologBlackboardHelper.wrapWithObjects(
            baseExpression,
            blackboardProvider().objects
        )
    }

    companion object {
        /**
         * Create a parser with rules provided directly as a string.
         */
        fun fromRules(
            rules: String,
            blackboardProvider: () -> Blackboard,
        ): PrologBlackboardExpressionParser {
            val baseParser = PrologLogicalExpressionParser.fromRules(rules)
            return PrologBlackboardExpressionParser(baseParser, blackboardProvider)
        }
    }
}
