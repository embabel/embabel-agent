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
import com.embabel.agent.api.common.workflow.WorkFlowBuilderReturning
import com.embabel.agent.api.common.workflow.WorkflowBuilder
import com.embabel.agent.api.dsl.AgentScopeBuilder
import java.util.function.Function
import java.util.function.Supplier

/**
 * Builder for creating a consensus workflow that generates results from multiple
 * but need not use an LLM at all.
 */
class ScatterGatherBuilder<ELEMENT : Any, RESULT : Any>(
    private val elementClass: Class<ELEMENT>,
    private val resultClass: Class<RESULT>,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {

    companion object : WorkFlowBuilderReturning {

        const val DEFAULT_MAX_CONCURRENCY = 6

        @JvmStatic
        override fun <RESULT : Any> returning(resultClass: Class<RESULT>): ElementBuilder<RESULT> {
            return ElementBuilder(resultClass)
        }

    }

    class ElementBuilder<RESULT : Any>(
        private val resultClass: Class<RESULT>,
    ) {

        fun <ELEMENT : Any> fromElements(elementClass: Class<ELEMENT>): ScatterGatherBuilder<ELEMENT, RESULT> {
            return ScatterGatherBuilder(elementClass, resultClass)
        }

    }

    fun withGenerators(
        generators: List<Function<out SupplierActionContext<ELEMENT>, ELEMENT>>,
    ): Generators {
        return Generators(
            generators = generators,
        )
    }

    // We avoid method overloading because it's evil
    fun generatedBy(
        generators: List<Supplier<ELEMENT>>,
    ): Generators {
        return Generators(
            generators = generators.map { generator ->
                Function<SupplierActionContext<ELEMENT>, ELEMENT> { context ->
                    generator.get()
                }
            },
        )
    }

    inner class Generators(
        private val generators: List<Function<out SupplierActionContext<ELEMENT>, ELEMENT>>,
    ) {

        fun consolidatedBy(
            consensusFunction: (TransformationActionContext<ResultList<ELEMENT>, RESULT>) -> RESULT,
        ): ScatterGatherWorkflowBuilder {
            return ScatterGatherWorkflowBuilder(generators, consensusFunction)
        }

    }

    inner class ScatterGatherWorkflowBuilder(
        private val generators: List<Function<out SupplierActionContext<ELEMENT>, ELEMENT>>,
        private val consensusFunction: (TransformationActionContext<ResultList<ELEMENT>, RESULT>) -> RESULT,
    ) : WorkflowBuilder<RESULT>(resultClass, inputClasses = emptyList()) {

        override fun build(): AgentScopeBuilder<RESULT> {
            return ScatterGather(maxConcurrency = maxConcurrency)
                .forkJoin(
                    generators = generators.map { it::apply } as List<(SupplierActionContext<ELEMENT>) -> ELEMENT>,
                    joinFunction = consensusFunction,
                    elementClass = elementClass,
                    resultClass = resultClass,
                )
        }
    }

}
