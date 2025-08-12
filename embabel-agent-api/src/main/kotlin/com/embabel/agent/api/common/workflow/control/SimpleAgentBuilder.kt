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
package com.embabel.agent.api.common.workflow.control

import com.embabel.agent.api.common.SupplierActionContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.SupplierAction
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.api.common.workflow.WorkFlowBuilderConsuming
import com.embabel.agent.api.common.workflow.WorkFlowBuilderReturning
import com.embabel.agent.api.common.workflow.WorkFlowBuilderWithInput
import com.embabel.agent.api.common.workflow.WorkflowBuilder
import com.embabel.agent.api.dsl.AgentScopeBuilder
import com.embabel.agent.core.Goal
import com.embabel.agent.core.IoBinding
import com.embabel.common.core.MobyNameGenerator

/**
 * Simplest way to build an agent that performs a single operation, like an LLM call.
 */
data class SimpleAgentBuilder<RESULT : Any>(
    private val resultClass: Class<RESULT>,
    private val inputClasses: List<Class<out Any>> = emptyList(),
) : WorkFlowBuilderConsuming, WorkFlowBuilderWithInput {

    companion object : WorkFlowBuilderReturning {

        /**
         * Creates a simple agent builder that can be used to build agents with a single action.
         * This is useful for quick prototyping or when you need a simple agent without complex workflows.
         */
        @JvmStatic
        override fun <RESULT : Any> returning(resultClass: Class<RESULT>): SimpleAgentBuilder<RESULT> {
            return SimpleAgentBuilder(resultClass)
        }
    }

    override fun withInput(inputClass: Class<out Any>): SimpleAgentBuilder<RESULT> {
        return copy(inputClasses = inputClasses + inputClass)
    }

    override fun <INPUT : Any> consuming(inputClass: Class<INPUT>): SimpleAgentConsumer<INPUT> {
        return SimpleAgentConsumer(inputClass)
    }

    /**
     * Provide a function the agent will perform.
     */
    fun running(
        generator: (SupplierActionContext<RESULT>) -> RESULT,
    ): WorkflowBuilder<RESULT> {
        return Emitter(generator)
    }

    inner class Emitter(
        private val generator: (SupplierActionContext<RESULT>) -> RESULT,
    ) : WorkflowBuilder<RESULT>(resultClass, inputClasses) {

        override fun build(): AgentScopeBuilder<RESULT> {
            val action = SupplierAction(
                name = "Generate ${resultClass.simpleName}",
                description = "Generates a result of type ${resultClass.simpleName}",
                cost = 0.0,
                value = 0.0,
                canRerun = true,
                pre = inputClasses.map { IoBinding(type = it).value },
                outputClass = resultClass,
                toolGroups = emptySet(),
            ) { context ->
                val supplierContext = SupplierActionContext(
                    processContext = context.processContext,
                    outputClass = resultClass,
                    action = context.action,
                )
                generator(supplierContext)
            }
            val goal = Goal(
                name = "${resultClass.simpleName} Goal",
                description = "Goal to generate a result of type ${resultClass.simpleName}",
                satisfiedBy = resultClass,
            )
            return AgentScopeBuilder(
                name = MobyNameGenerator.generateName(),
                actions = listOf(action),
                goals = setOf(goal),
            )
        }
    }

    inner class SimpleAgentConsumer<INPUT : Any>(
        private val inputClass: Class<INPUT>,
    ) {

        /**
         * Provide a function the agent will perform.
         */
        fun running(
            generator: (TransformationActionContext<INPUT, RESULT>) -> RESULT,
        ): WorkflowBuilder<RESULT> {
            return Emitter(generator)
        }

        inner class Emitter(
            private val generator: (TransformationActionContext<INPUT, RESULT>) -> RESULT,
        ) : WorkflowBuilder<RESULT>(resultClass, inputClasses) {

            override fun build(): AgentScopeBuilder<RESULT> {
                val action = TransformationAction(
                    name = "Generate ${resultClass.simpleName}",
                    description = "Generates a result of type ${resultClass.simpleName}",
                    cost = 0.0,
                    value = 0.0,
                    canRerun = true,
                    inputClass = inputClass,
                    outputClass = resultClass,
                    toolGroups = emptySet(),
                ) { context ->
                    generator(context)
                }
                val goal = Goal(
                    name = "${resultClass.simpleName} Goal",
                    description = "Goal to generate a result of type ${resultClass.simpleName}",
                    satisfiedBy = resultClass,
                )
                return AgentScopeBuilder(
                    name = MobyNameGenerator.generateName(),
                    actions = listOf(action),
                    goals = setOf(goal),
                )
            }
        }
    }


}
