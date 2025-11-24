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
import org.slf4j.LoggerFactory

/**
 * A logical expression backed by a Prolog query.
 *
 * This expression can:
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
     * The determineCondition function is called for any conditions referenced in the query.
     * These conditions are asserted as Prolog facts before executing the query.
     *
     * For example, if the query is "can_approve(User)" and it depends on conditions like
     * "windowIsOpen", the determineCondition function will be called to check those conditions.
     * All zero-arity predicates not defined in the Prolog rules are considered condition references.
     */
    override fun evaluate(determineCondition: (String) -> ConditionDetermination): ConditionDetermination {
        return try {
            // Extract undefined predicates from both the query and the rules
            val queryPredicates = engine.extractZeroArityPredicates(query)
            val rulePredicates = engine.extractUndefinedPredicatesFromRules()
            val allUndefined = (queryPredicates + rulePredicates).filter { !engine.isPredicateDefined(it) }

            logger.debug("Undefined predicates to resolve: {}", allUndefined)

            // Resolve conditions and assert facts for TRUE ones
            var scopedEngine = engine
            var hasUnknownCondition = false

            for (predicateName in allUndefined) {
                val determination = determineCondition(predicateName)
                logger.debug("Condition '{}' resolved to {}", predicateName, determination)

                when (determination) {
                    ConditionDetermination.TRUE -> {
                        // Assert as a fact
                        scopedEngine = scopedEngine.assertFact(predicateName)
                    }
                    ConditionDetermination.UNKNOWN -> {
                        logger.warn("Condition '{}' could not be resolved (UNKNOWN)", predicateName)
                        hasUnknownCondition = true
                    }
                    ConditionDetermination.FALSE -> {
                        // For FALSE, we don't assert anything - Prolog will fail to find it
                        logger.debug("Condition '{}' is FALSE, not asserting", predicateName)
                    }
                }
            }

            // If any condition was unknown, return UNKNOWN overall
            if (hasUnknownCondition) {
                logger.warn("Prolog query '{}' contains unresolved conditions, returning UNKNOWN", query)
                return ConditionDetermination.UNKNOWN
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
     * Evaluate with objects that should be converted to facts.
     * This is useful when the query depends on domain objects.
     */
    fun evaluateWithObjects(
        vararg objects: Any,
        determineCondition: (String) -> ConditionDetermination = { ConditionDetermination.UNKNOWN }
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
