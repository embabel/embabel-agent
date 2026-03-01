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
package com.embabel.agent.spi.expression.spel

import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.expression.LogicalExpression
import com.embabel.common.util.loggerFor
import com.embabel.plan.common.condition.ConditionDetermination
import org.slf4j.event.Level
import org.springframework.expression.ExpressionParser
import org.springframework.context.expression.MapAccessor
import org.springframework.expression.spel.SpelEvaluationException
import org.springframework.expression.spel.SpelMessage
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext

internal class SpelLogicalExpression(
    private val expression: String,
) : LogicalExpression {

    private val logger = loggerFor<SpelLogicalExpression>()

    override fun evaluate(blackboard: Blackboard): ConditionDetermination {
        return try {
            val model = blackboard.expressionEvaluationModel().toMutableMap()

            // Add objects from blackboard by their simple class names (lowercase)
            // This allows expressions like "elephant.age > 20" to work
            blackboard.objects.forEach { obj ->
                val simpleName = obj::class.simpleName?.replaceFirstChar { it.lowercase() }
                if (simpleName != null && !model.containsKey(simpleName)) {
                    model[simpleName] = obj
                }
            }

            val context = StandardEvaluationContext()
            // Allow dot-notation property access on Map objects (e.g., DynamicType payloads)
            context.addPropertyAccessor(MapAccessor())
            // Add each entry as a variable to the context
            model.forEach { (key, value) ->
                context.setVariable(key, value)
            }

            // Prefix variables with # in SpEL, so convert expression
            // Only replace the first identifier if it's a variable we know about
            // For example: "elephant.age > 20" becomes "#elephant.age > 20"
            val spelExpression = if (!expression.startsWith("#")) {
                // Find variable references that are not preceded by a dot (property access)
                expression.replace(Regex("(?<![.#])\\b([a-zA-Z_][a-zA-Z0-9_]*)(?=\\.|\\s|[><=!])")) { matchResult ->
                    val varName = matchResult.value
                    // Check if this is a variable name we have in the model
                    if (model.containsKey(varName)) {
                        "#$varName"
                    } else {
                        varName
                    }
                }
            } else {
                expression
            }

            when (val result = parser.parseExpression(spelExpression).getValue(context)) {
                is Boolean -> if (result) ConditionDetermination.TRUE else ConditionDetermination.FALSE
                null -> ConditionDetermination.UNKNOWN
                else -> ConditionDetermination.UNKNOWN
            }
        } catch (e: SpelEvaluationException) {
            // If evaluation fails, return UNKNOWN
            val level = when (e.messageCode) {
                SpelMessage.PROPERTY_OR_FIELD_NOT_READABLE_ON_NULL -> {
                    // This is not an error, just means something was null
                    Level.DEBUG
                }

                else -> Level.WARN
            }
            logger.atLevel(level).log(
                "Failed to evaluate SpEL expression '{}': {}",
                expression,
                e.message,
                e,
            )
            ConditionDetermination.UNKNOWN
        } catch (e: Exception) {
            logger.warn(
                "Failed to evaluate SpEL expression '{}': {}",
                expression,
                e.message,
                e,
            )
            ConditionDetermination.UNKNOWN
        }
    }

    companion object {
        private val parser: ExpressionParser = SpelExpressionParser()
    }
}
