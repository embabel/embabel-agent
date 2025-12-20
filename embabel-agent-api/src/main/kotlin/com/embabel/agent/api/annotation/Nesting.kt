package com.embabel.agent.api.annotation

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.Agent


/**
 * Internal exception to signal sub-agent execution.
 */
class SubagentExecutionRequest(
    val instance: Any,
    type: Class<*>,
) : SpecialReturnException("Subagent execution for instance: $instance", type) {

    override fun handle(actionContext: ActionContext): Any {
        val agent: Agent = when (instance) {
            is Agent -> instance
            else -> {
                AgentMetadataReader().createAgentMetadata(instance) as Agent
            }
        }
        return actionContext.asSubProcess(type, agent)
    }
}

object Nesting {

    /**
     * Run the instance as a sub-agent and return the result.
     * The instance must be an @Agent-annotated class or a com.embabel.agent.core.Agent instance.
     */
    @JvmStatic
    @Throws(SubagentExecutionRequest::class)
    fun <T : Any> runSubagent(
        instance: Any,
        type: Class<T>,
    ): T {
        when (instance) {
            is SubagentExecutionRequest -> throw instance
            else -> throw SubagentExecutionRequest(instance, type)
        }
    }
}