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
package com.embabel.agent.api.common.workflow.loop

import com.embabel.agent.api.common.workflow.WorkFlowBuilderConsuming
import com.embabel.agent.api.common.workflow.WorkflowBuilder
import com.embabel.agent.api.common.workflow.WorkflowBuilderReturning
import com.embabel.agent.api.dsl.AgentScopeBuilder

/**
 * Java friendly builder for RepeatUntil workflow.
 */
data class RepeatUntilBuilder<INPUT, RESULT : Any>(
    private val resultClass: Class<RESULT>,
    private val inputClass: Class<out INPUT>? = null,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) : WorkFlowBuilderConsuming {

    companion object : WorkflowBuilderReturning {

        const val DEFAULT_MAX_ITERATIONS = 5

        /**
         * Create a RepeatUntilBuilder for a specific result type and default TextFeedback.
         */
        @JvmStatic
        override fun <RESULT : Any> returning(resultClass: Class<RESULT>): RepeatUntilBuilder<Any?, RESULT> {
            return RepeatUntilBuilder(resultClass = resultClass)
        }
    }

    override fun <INPUT : Any> consuming(inputClass: Class<INPUT>): RepeatUntilBuilder<INPUT, RESULT> {
        return RepeatUntilBuilder(
            resultClass = resultClass,
            inputClass = inputClass,
            maxIterations = maxIterations,
        )
    }

    fun withMaxIterations(maxIterations: Int): RepeatUntilBuilder<INPUT, RESULT> =
        copy(maxIterations = maxIterations)

    /**
     * Define the task to be repeated until an acceptable result is achieved.
     */
    fun repeating(
        what: (RepeatUntilActionContext<INPUT, RESULT>) -> RESULT,
    ): Looper {
        return Looper(generator = what)
    }

    inner class Looper(
        private val generator: (RepeatUntilActionContext<INPUT, RESULT>) -> RESULT,
    ) {

        /**
         * Define the acceptance criteria for the feedback.
         * This will determine when the generated result is considered acceptable.
         */
        fun until(
            accept: (RepeatUntilActionContext<INPUT, RESULT>) -> Boolean,
        ): Emitter {
            return Emitter(generator, accept)
        }
    }

    inner class Emitter(
        private val generator: (RepeatUntilActionContext<INPUT, RESULT>) -> RESULT,
        private val accept: (RepeatUntilActionContext<INPUT, RESULT>) -> Boolean,
    ) : WorkflowBuilder<RESULT>(resultClass, inputClass) {

        /**
         * Build the workflow so it can be included in agents
         */
        override fun build(): AgentScopeBuilder<RESULT> {
            return RepeatUntil(maxIterations = maxIterations)
                .build(
                    task = generator,
                    accept = accept,
                    resultClass = resultClass,
                    inputClass = inputClass,
                )
        }

    }
}
