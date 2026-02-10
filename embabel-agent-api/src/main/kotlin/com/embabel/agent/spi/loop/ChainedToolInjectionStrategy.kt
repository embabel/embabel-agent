/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.Tool

/**
 * Chains multiple injection strategies into a pipeline.
 *
 * Evaluates strategies in order and combines their results.
 * Tool additions are accumulated, tool removals are accumulated.
 *
 * @param strategies The strategies to evaluate in order
 */
class ChainedToolInjectionStrategy(
    private val strategies: List<ToolInjectionStrategy>,
) : ToolInjectionStrategy {

    constructor(vararg strategies: ToolInjectionStrategy) : this(strategies.toList())

    override fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
        val allToAdd = mutableListOf<Tool>()
        val allToRemove = mutableListOf<Tool>()

        for (strategy in strategies) {
            val result = strategy.evaluate(context)
            allToAdd.addAll(result.toolsToAdd)
            allToRemove.addAll(result.toolsToRemove)
        }

        return ToolInjectionResult(
            toolsToAdd = allToAdd,
            toolsToRemove = allToRemove,
        )
    }

    companion object {

        /**
         * Create a chained strategy that includes UnfoldingTool support plus custom strategies.
         */
        @JvmStatic
        fun withUnfolding(vararg additionalStrategies: ToolInjectionStrategy): ChainedToolInjectionStrategy {
            return ChainedToolInjectionStrategy(
                listOf(UnfoldingToolInjectionStrategy.INSTANCE) + additionalStrategies.toList()
            )
        }

        /**
         * @deprecated Use [withUnfolding] instead.
         */
        @Deprecated(
            message = "Use withUnfolding() instead",
            replaceWith = ReplaceWith("withUnfolding(*additionalStrategies)")
        )
        @JvmStatic
        fun withMatryoshka(vararg additionalStrategies: ToolInjectionStrategy): ChainedToolInjectionStrategy =
            withUnfolding(*additionalStrategies)
    }
}
