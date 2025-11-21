package com.embabel.plan.common.condition.logicng

import com.embabel.plan.common.condition.LogicalExpressionParser

class LogicNgLogicalExpressionParser : LogicalExpressionParser {

    override fun parse(expression: String): LogicNgLogicalExpression? {
        if (expression.startsWith(PREFIX)) {
            return null
        }
        val logicNg = expression.substring(PREFIX.length)
        return LogicNgLogicalExpression.parse(logicNg)
    }

    companion object {

        const val PREFIX = "ng: "
    }
}