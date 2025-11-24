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
package com.embabel.agent.spi.expression.prolog

import com.embabel.agent.core.Blackboard
import com.embabel.agent.spi.expression.LogicalExpression
import com.embabel.plan.common.condition.ConditionDetermination
import org.slf4j.LoggerFactory

/**
 * A logical expression backed by a Prolog query.
 *
 * This expression can:r
 * - Use Prolog rules loaded into the engine
 * - Reference conditions from the world state as Prolog facts
 * - Evaluate complex logical relationships
 *
 * @param query The Prolog query to evaluate
 * @param engine The Prolog engine with pre-loaded rules
 * @param factConverter Converter for objects to Prolog facts
 */
class PrologLogicalExpression(
    private val query: String,
    private val engine: TuPrologEngine,
    private val factConverter: PrologFactConverter = PrologFactConverter(),
) : LogicalExpression {

    private val logger = LoggerFactory.getLogger(PrologLogicalExpression::class.java)

    /**
     * Evaluate the Prolog query.
     *
     * Zero-arity predicates not defined in the Prolog rules are looked up as conditions
     * from the blackboard. If any such condition cannot be determined (not set in blackboard),
     * the overall result is UNKNOWN.
     *
     * For example, if the query is "can_enter" and it depends on conditions like
     * "windowIsOpen" and "hasPermission", these will be looked up from the blackboard.
     */
    override fun evaluate(blackboard: Blackboard): ConditionDetermination {
        return try {
            // Start with the base engine
            var scopedEngine = engine

            // Get blackboard objects and convert them to Prolog facts
            val blackboardObjects = blackboard.objects
            if (blackboardObjects.isNotEmpty()) {
                val facts = blackboardObjects.flatMap { factConverter.convertToFacts(it) }
                logger.debug("Asserting {} facts from {} blackboard objects", facts.size, blackboardObjects.size)
                scopedEngine = scopedEngine.assertFacts(facts)
            }

            // Extract zero-arity predicates that might be conditions
            val undefinedPredicates = scopedEngine.extractUndefinedPredicatesFromRules()
            val zeroArityPredicates = scopedEngine.extractZeroArityPredicates(query)
            val potentialConditions = (undefinedPredicates + zeroArityPredicates)
                .filter { scopedEngine.isPredicateDefined(it).not() }

            // Look up conditions from blackboard and assert them as facts
            for (condition in potentialConditions) {
                val conditionValue = blackboard.getCondition(condition)
                if (conditionValue == null) {
                    logger.warn("Condition '{}' referenced in query but not set in blackboard", condition)
                    return ConditionDetermination.UNKNOWN
                }
                // Assert the condition as a Prolog fact
                scopedEngine = if (conditionValue) {
                    scopedEngine.assertFacts(listOf(condition))
                } else {
                    // Don't assert false conditions - they just won't be present
                    scopedEngine
                }
                logger.debug("Resolved condition '{}' to {}", condition, conditionValue)
            }

            // Execute the query
            val result = scopedEngine.query(query)

            logger.debug("Prolog query '{}' evaluated to {}", query, result)

            ConditionDetermination(result)
        } catch (e: Exception) {
            logger.error("Error evaluating Prolog query: {}", query, e)
            ConditionDetermination.UNKNOWN
        }
    }

    /**
     * Set the blackboard objects to be converted to facts during evaluation.
     * This should be called before evaluate() to provide domain objects.
     */
    fun withObjects(vararg objects: Any): PrologLogicalExpression {
        val facts = objects.flatMap { factConverter.convertToFacts(it) }
        val newEngine = engine.assertFacts(facts)
        return PrologLogicalExpression(query, newEngine, factConverter)
    }

    /**
     * Evaluate with objects that should be converted to facts.
     * This is useful when the query depends on domain objects.
     */
    fun evaluateWithObjects(
        vararg objects: Any,
        determineCondition: (String) -> ConditionDetermination = { ConditionDetermination.UNKNOWN },
    ): ConditionDetermination {
        return try {
            // Assert facts from all provided objects
            var scopedEngine = engine
            objects.forEach { obj ->
                val facts = factConverter.convertToFacts(obj)
                scopedEngine = scopedEngine.assertFacts(facts)
            }

            // Execute the query
            val result = scopedEngine.query(query)

            logger.debug("Prolog query '{}' with {} objects evaluated to {}", query, objects.size, result)

            ConditionDetermination(result)
        } catch (e: Exception) {
            logger.error("Error evaluating Prolog query with objects: {}", query, e)
            ConditionDetermination.UNKNOWN
        }
    }

    override fun toString(): String = "PrologExpression($query)"
}
