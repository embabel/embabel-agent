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
 * Implemented by classes that can parse logical expressions from strings.
 */
interface LogicalExpressionParser {

    /**
     * Parse the given expression string into a [LogicalExpression].
     * Returns null if the expression cannot be parsed.
     * Typically, there is a prefix or syntax that the parser recognizes.
     */
    fun parse(expression: String): LogicalExpression?

    companion object {

        /**
         * Create a [LogicalExpressionParser] that tries multiple parsers in order.
         * The first parser that successfully parses the expression is used.
         */
        fun of(vararg parsers: LogicalExpressionParser): LogicalExpressionParser =
            MultiLogicalExpressionParser(parsers.toList())

        /**
         * A parser that always returns null (cannot parse any expression).
         */
        @JvmStatic
        val EMPTY: LogicalExpressionParser = EmptyLogicalExpressionParser
    }
}

private object EmptyLogicalExpressionParser : LogicalExpressionParser {
    override fun parse(expression: String) = null
}

private class MultiLogicalExpressionParser(
    private val parsers: List<LogicalExpressionParser>,
) : LogicalExpressionParser {

    override fun parse(expression: String): LogicalExpression? {
        for (parser in parsers) {
            val result = parser.parse(expression)
            if (result != null) {
                return result
            }
        }
        return null
    }
}
