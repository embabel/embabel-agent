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

import it.unibo.tuprolog.core.Atom
import it.unibo.tuprolog.core.Struct
import it.unibo.tuprolog.core.Term
import it.unibo.tuprolog.core.parsing.ParseException
import it.unibo.tuprolog.core.parsing.parse
import it.unibo.tuprolog.solve.Solution
import it.unibo.tuprolog.solve.Solver
import it.unibo.tuprolog.solve.classic.ClassicSolverFactory
import it.unibo.tuprolog.theory.Theory
import it.unibo.tuprolog.theory.parsing.parse
import org.slf4j.LoggerFactory

/**
 * Wrapper around tuProlog solver for evaluating Prolog queries.
 * Provides a simplified interface for loading rules, asserting facts, and querying.
 *
 * This class is immutable - asserting facts returns a new instance.
 */
class TuPrologEngine private constructor(
    private val baseRules: String,
    private val dynamicFacts: List<String>,
) {
    private val logger = LoggerFactory.getLogger(TuPrologEngine::class.java)

    // Lazy solver that combines rules and facts
    private val solver: Solver by lazy {
        val allTheory = buildTheory()
        ClassicSolverFactory.solverWithDefaultBuiltins(staticKb = allTheory)
    }

    private fun buildTheory(): Theory {
        val combined = if (dynamicFacts.isEmpty()) {
            baseRules
        } else {
            val factsString = dynamicFacts.joinToString("\n") { "$it." }
            if (baseRules.isNotBlank()) {
                "$factsString\n\n$baseRules"
            } else {
                factsString
            }
        }

        return if (combined.isNotBlank()) {
            try {
                Theory.parse(combined)
            } catch (e: ParseException) {
                logger.error("Failed to parse Prolog theory", e)
                Theory.empty()
            }
        } else {
            Theory.empty()
        }
    }

    /**
     * Query the Prolog engine with a goal.
     * Returns true if the query succeeds, false otherwise.
     */
    fun query(queryString: String): Boolean {
        return try {
            val goal = Struct.parse(queryString)
            val solutions = solver.solve(goal).toList()
            val hasSuccess = solutions.any { it is Solution.Yes }
            logger.debug("Query: {} -> {}", queryString, hasSuccess)
            hasSuccess
        } catch (e: ParseException) {
            logger.error("Failed to parse query: {}", queryString, e)
            false
        } catch (e: Exception) {
            logger.error("Error executing query: {}", queryString, e)
            false
        }
    }

    /**
     * Assert a fact into the knowledge base.
     * The fact should be a valid Prolog term string WITHOUT the trailing period.
     * Returns a new TuPrologEngine instance with the fact added.
     */
    fun assertFact(fact: String): TuPrologEngine {
        return try {
            // Validate the fact can be parsed
            Struct.parse(fact)
            TuPrologEngine(baseRules, dynamicFacts + fact)
        } catch (e: ParseException) {
            logger.error("Failed to parse fact: {}", fact, e)
            this
        } catch (e: Exception) {
            logger.error("Error asserting fact: {}", fact, e)
            this
        }
    }

    /**
     * Assert multiple facts at once.
     */
    fun assertFacts(facts: List<String>): TuPrologEngine {
        val validFacts = facts.filter { it.isNotBlank() }
        return if (validFacts.isEmpty()) {
            this
        } else {
            TuPrologEngine(baseRules, dynamicFacts + validFacts)
        }
    }

    /**
     * Extract zero-arity predicates (atoms) from a query term.
     * These are potential condition references that need to be resolved.
     */
    fun extractZeroArityPredicates(queryString: String): Set<String> {
        return try {
            val term = Struct.parse(queryString)
            val atoms = mutableSetOf<String>()
            collectAtoms(term, atoms)
            atoms
        } catch (e: Exception) {
            logger.error("Failed to extract predicates from query: {}", queryString, e)
            emptySet()
        }
    }

    /**
     * Extract all undefined zero-arity predicates from the rules.
     * These are predicates referenced in rules but not defined anywhere.
     */
    fun extractUndefinedPredicatesFromRules(): Set<String> {
        val allPredicates = mutableSetOf<String>()
        val definedPredicates = mutableSetOf<String>()

        try {
            val theory = buildTheory()

            // Collect all predicates referenced in rule bodies and defined in heads
            theory.forEach { clause ->
                // Collect defined predicate from head
                val head = clause.head
                when (head) {
                    is Atom -> definedPredicates.add(head.value)
                    is Struct -> {
                        if (head.arity == 0) {
                            definedPredicates.add(head.functor)
                        } else {
                            definedPredicates.add(head.functor)
                        }
                    }
                }

                // Collect all predicates referenced in body
                collectAtoms(clause.body, allPredicates, isGoalPosition = true)
            }

            // Return predicates that are referenced but not defined
            return allPredicates - definedPredicates
        } catch (e: Exception) {
            logger.debug("Error extracting undefined predicates from rules", e)
            return emptySet()
        }
    }

    private fun collectAtoms(
        term: Term,
        atoms: MutableSet<String>,
        isGoalPosition: Boolean = true,
    ) {
        when (term) {
            is Atom -> {
                // Only collect atoms that are in goal position (predicates)
                if (isGoalPosition && term.value != "true" && term.value != "false") {
                    atoms.add(term.value)
                }
            }

            is Struct -> {
                val functor = term.functor

                // Handle logical operators specially - their arguments are goals
                when {
                    functor == "," || functor == ";" || functor == "->" -> {
                        // Conjunction, disjunction, if-then: arguments are goals
                        term.args.forEach { collectAtoms(it, atoms, isGoalPosition = true) }
                    }

                    term.arity == 0 && functor != "true" && functor != "false" && isGoalPosition -> {
                        // Zero-arity predicate in goal position
                        atoms.add(functor)
                    }

                    else -> {
                        // Regular struct with arguments - arguments are NOT goals
                        // Don't collect anything from arguments
                    }
                }
            }
        }
    }

    /**
     * Check if a predicate is defined in the base rules.
     * This checks both the static rules and dynamic facts.
     */
    fun isPredicateDefined(predicateName: String): Boolean {
        // Check if it's in dynamic facts
        if (dynamicFacts.any { it.startsWith(predicateName) && (it == predicateName || it[predicateName.length] == '(') }) {
            return true
        }

        // Check if it's defined in base rules by trying to parse the theory
        return try {
            val theory = buildTheory()
            theory.any { clause ->
                val head = clause.head
                when (head) {
                    is Atom -> head.value == predicateName
                    is Struct -> head.functor == predicateName
                    else -> false
                }
            }
        } catch (e: Exception) {
            logger.debug("Error checking if predicate {} is defined", predicateName, e)
            false
        }
    }

    companion object {

        /**
         * Create a new Prolog engine with the given rules.
         * @param rules Prolog rules as a string
         */
        fun create(rules: String = ""): TuPrologEngine {
            return TuPrologEngine(rules, emptyList())
        }
    }
}
