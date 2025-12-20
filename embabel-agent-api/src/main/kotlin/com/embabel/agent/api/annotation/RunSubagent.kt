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

/**
 * Convenience methods for nesting agents for use with annotation model.
 */
object RunSubagent {

    /**
     * Run the agent instance as a subagent and return the result.
     * @param agent The agent instance to run as a subagent.
     * @param type The expected return type.
     */
    @JvmStatic
    @Throws(SubagentExecutionRequest::class)
    fun <T : Any> instance(
        agent: Agent,
        type: Class<T>,
    ): T = runAnySubagent(agent, type)

    inline fun <reified T : Any> instance(
        instance: Agent,
    ): T = instance(instance, T::class.java)

    /**
     * Run the @Agent annotated instance as a subagent and return the result.
     * @param instance The @Agent annotated instance to run as a subagent.
     * @param type The expected return type.
     */
    @JvmStatic
    @Throws(SubagentExecutionRequest::class)
    fun <T : Any> fromAnnotatedInstance(
        instance: Any,
        type: Class<T>,
    ): T = runAnySubagent(instance, type)

    inline fun <reified T : Any> fromAnnotatedInstance(
        instance: Any,
    ): T = fromAnnotatedInstance(instance, T::class.java)

    @Throws(SubagentExecutionRequest::class)
    private fun <T : Any> runAnySubagent(
        instance: Any,
        type: Class<T>,
    ): T {
        when (instance) {
            is SubagentExecutionRequest -> throw instance
            else -> throw SubagentExecutionRequest(instance, type)
        }
    }
}
