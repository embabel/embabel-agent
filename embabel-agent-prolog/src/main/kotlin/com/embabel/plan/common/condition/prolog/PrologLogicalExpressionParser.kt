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

import com.embabel.plan.common.condition.LogicalExpression
import com.embabel.plan.common.condition.LogicalExpressionParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Parses logical expressions with Prolog syntax.
 *
 * Recognizes expressions with the "prolog:" prefix.
 * Example: "prolog:can_approve(alice)"
 *
 * @param engine The Prolog engine with pre-loaded rules to use for evaluation
 */
@Service
class PrologLogicalExpressionParser(
    private val engine: TuPrologEngine,
) : LogicalExpressionParser {

    private val logger = LoggerFactory.getLogger(PrologLogicalExpressionParser::class.java)

    companion object {
        const val PREFIX = "prolog:"

        /**
         * Create a parser with rules loaded from a file.
         */
        fun fromFile(filePath: String): PrologLogicalExpressionParser {
            val loader = PrologRuleLoader()
            val rules = loader.loadFromFile(filePath)
            val engine = TuPrologEngine.create(rules)
            return PrologLogicalExpressionParser(engine)
        }

        /**
         * Create a parser with rules loaded from a classpath resource.
         */
        fun fromResource(resourcePath: String): PrologLogicalExpressionParser {
            val loader = PrologRuleLoader()
            val rules = loader.loadFromResource(resourcePath)
            val engine = TuPrologEngine.create(rules)
            return PrologLogicalExpressionParser(engine)
        }

        /**
         * Create a parser with rules loaded from a class resource.
         */
        fun fromClassResource(
            clazz: Class<*>,
            filename: String,
        ): PrologLogicalExpressionParser {
            val loader = PrologRuleLoader()
            val rules = loader.loadFromClassResource(clazz, filename)
            val engine = TuPrologEngine.create(rules)
            return PrologLogicalExpressionParser(engine)
        }

        /**
         * Create a parser with rules provided directly as a string.
         */
        fun fromRules(rules: String): PrologLogicalExpressionParser {
            val engine = TuPrologEngine.create(rules)
            return PrologLogicalExpressionParser(engine)
        }
    }

    /**
     * Parse an expression string.
     * Returns a PrologLogicalExpression if the expression starts with "prolog:",
     * otherwise returns null.
     */
    override fun parse(expression: String): LogicalExpression? {
        if (!expression.startsWith(PREFIX)) {
            return null
        }

        val query = expression.substring(PREFIX.length).trim()
        if (query.isBlank()) {
            logger.warn("Empty Prolog query after prefix: {}", expression)
            return null
        }

        logger.debug("Parsed Prolog expression: {}", query)
        return PrologLogicalExpression(query, engine)
    }
}
