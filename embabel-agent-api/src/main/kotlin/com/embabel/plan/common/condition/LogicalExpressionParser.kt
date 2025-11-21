package com.embabel.plan.common.condition

interface LogicalExpressionParser {

    fun parse(expression: String): LogicalExpression?

    companion object {

        fun of(vararg parsers: LogicalExpressionParser): LogicalExpressionParser =
            MultiLogicalExpressionParser(parsers.toList())
    }
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
