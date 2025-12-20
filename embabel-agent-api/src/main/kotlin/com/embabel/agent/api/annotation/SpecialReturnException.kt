package com.embabel.agent.api.annotation

import com.embabel.agent.api.common.ActionContext

/**
 * Superclass for exceptions thrown by special return mechanisms like subagent execution.
 */
abstract class SpecialReturnException(
    message: String,
    val type: Class<*>,
) : RuntimeException(message) {

    /**
     * Process the special return and produce the final result.
     */
    abstract fun handle(actionContext: ActionContext): Any
}