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
package com.embabel.agent.api.common.workflow.multimodel

import com.embabel.agent.api.common.SupplierActionContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.workflow.WorkFlowBuilderReturning
import com.embabel.agent.api.common.workflow.WorkflowBuilder
import com.embabel.agent.api.common.workflow.control.ResultList
import com.embabel.agent.api.common.workflow.control.ScatterGather
import com.embabel.agent.api.dsl.AgentScopeBuilder
import java.util.function.Supplier

/**
 * Builder for creating a consensus workflow that generates results from multiple generators
 * Generators and consensus function are typically used in multi-model scenarios,
 * but need not use an LLM at all.
 */
class ConsensusBuilder<RESULT : Any>(
    private val resultClass: Class<RESULT>,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {

    companion object : WorkFlowBuilderReturning {

        const val DEFAULT_MAX_CONCURRENCY = 6

        @JvmStatic
        override fun <RESULT : Any> returning(resultClass: Class<RESULT>): ConsensusBuilder<RESULT> {
            return ConsensusBuilder(resultClass)
        }

    }

    fun withSources(
        generators: List<java.util.function.Function<out SupplierActionContext<RESULT>, RESULT>>,
    ): Generators {
        return Generators(
            generators = generators,
        )
    }

    // We avoid method overloading because it's evil
    fun sourcedFrom(
        generators: List<Supplier<RESULT>>,
    ): Generators {
        return Generators(
            generators = generators.map { generator ->
                java.util.function.Function<SupplierActionContext<RESULT>, RESULT> { context ->
                    generator.get()
                }
            },
        )
    }

    inner class Generators(
        private val generators: List<java.util.function.Function<out SupplierActionContext<RESULT>, RESULT>>,
    ) {

        fun withConsensusBy(
            consensusFunction: (TransformationActionContext<ResultList<RESULT>, RESULT>) -> RESULT,
        ): ConsensusSpec {
            return ConsensusSpec(generators, consensusFunction)
        }

    }

    inner class ConsensusSpec(
        private val generators: List<java.util.function.Function<out SupplierActionContext<RESULT>, RESULT>>,
        private val consensusFunction: (TransformationActionContext<ResultList<RESULT>, RESULT>) -> RESULT,
    ) : WorkflowBuilder<RESULT>(resultClass, inputClasses = emptyList()) {

        override fun build(): AgentScopeBuilder<RESULT> {
            return ScatterGather(maxConcurrency = maxConcurrency)
                .forkJoin(
                    generators = generators.map { it::apply } as List<(SupplierActionContext<RESULT>) -> RESULT>,
                    joinFunction = consensusFunction,
                    elementClass = resultClass,
                    resultClass = resultClass,
                )
        }
    }

}
