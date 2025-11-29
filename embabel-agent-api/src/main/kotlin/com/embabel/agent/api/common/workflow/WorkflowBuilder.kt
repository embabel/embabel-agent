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
package com.embabel.agent.api.common.workflow

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.dsl.TypedAgentScopeBuilder
import com.embabel.agent.core.Agent
import com.embabel.agent.core.IoBinding
import com.embabel.agent.spi.common.Constants

/**
 * Ensure consistent naming convention for workflow builders that return a given result type.
 */
interface WorkflowBuilderReturning {

    fun <RESULT : Any> returning(resultClass: Class<RESULT>): Any
}

/**
 * Ensure consistent naming convention for workflow builders that consume a given input type.
 */
interface WorkflowBuilderConsuming {

    /**
     * Specify the input type for this workflow.
     * Return a builder
     */
    fun <INPUT : Any> consuming(inputClass: Class<INPUT>): Any
}

/**
 * Common base class for building workflows,
 * ensuring consistent agent construction
 */
abstract class WorkflowBuilder<RESULT : Any>(
    private val resultClass: Class<RESULT>,
    private val inputClass: Class<out Any>?,
) {

    abstract fun build(): TypedAgentScopeBuilder<RESULT>

    /**
     * Build an agent on this RepeatUntil workflow.
     * Can be used to implement an @Bean method that returns an Agent,
     * which will be automatically be registered on the current AgentPlatform.
     */
    fun buildAgent(
        name: String,
        description: String,
    ): Agent {
        return build()
            .build()
            .createAgent(
                name = name,
                provider = Constants.EMBABEL_PROVIDER,
                description = description,
            )
    }

    /**
     * Convenience method to build an agent with a default name and description.
     * This is typically used inside an @Action method.
     */
    fun asSubProcess(
        context: ActionContext,
    ): RESULT {
        val preconditions = listOfNotNull(inputClass).map { IoBinding(type = it) }.map { it.value }
        val illegals = preconditions.filter { context.action?.preconditions?.contains(it) != true }
        if (illegals.isNotEmpty()) {
            error(
                """
                Cannot build a sub-process with input classes not specified as preconditions of enclosing action.
                Illegal preconditions: ${illegals.joinToString(", ")}
                Use buildAgent() instead.
                """.trimIndent()
            )
        }
        return build()
            .asSubProcess(context, resultClass)
    }

}
