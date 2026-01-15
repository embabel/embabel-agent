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

import com.embabel.agent.api.tool.MatryoshkaTool
import org.slf4j.LoggerFactory

/**
 * Injection strategy that handles [MatryoshkaTool] invocations.
 *
 * When a MatryoshkaTool is invoked:
 * 1. Its selected inner tools are added to the available tools
 * 2. If [MatryoshkaTool.removeOnInvoke] is true, the facade is removed
 *
 * This enables progressive tool disclosure - presenting simplified categories
 * initially, then revealing granular tools when the LLM expresses intent.
 *
 * Example flow:
 * 1. LLM sees: "database_operations" tool
 * 2. LLM invokes: database_operations
 * 3. Result: database_operations removed, query_table/insert/update/delete added
 * 4. LLM can now use specific database tools
 *
 * This strategy can be combined with other strategies using [CompositeToolInjectionStrategy].
 *
 * @see MatryoshkaTool
 */
class MatryoshkaToolInjectionStrategy : ToolInjectionStrategy {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
        // Find the invoked tool
        val invokedTool = context.currentTools.find {
            it.definition.name == context.lastToolCall.toolName
        }

        // Only handle MatryoshkaTool invocations
        if (invokedTool !is MatryoshkaTool) {
            return ToolInjectionResult.noChange()
        }

        // Select tools based on input
        val selectedTools = invokedTool.selectTools(context.lastToolCall.toolInput)

        if (selectedTools.isEmpty()) {
            logger.warn(
                "MatryoshkaTool '{}' selected no inner tools for input: {}",
                invokedTool.definition.name,
                context.lastToolCall.toolInput
            )
            // Still remove the facade if configured, even with no tools selected
            return if (invokedTool.removeOnInvoke) {
                ToolInjectionResult.remove(listOf(invokedTool))
            } else {
                ToolInjectionResult.noChange()
            }
        }

        logger.debug(
            "MatryoshkaTool '{}' exposing {} tools: {}",
            invokedTool.definition.name,
            selectedTools.size,
            selectedTools.map { it.definition.name }
        )

        return if (invokedTool.removeOnInvoke) {
            ToolInjectionResult.replace(invokedTool, selectedTools)
        } else {
            ToolInjectionResult.add(selectedTools)
        }
    }

    companion object {
        /**
         * Singleton instance for convenience.
         */
        @JvmField
        val INSTANCE = MatryoshkaToolInjectionStrategy()
    }
}

/**
 * Combines multiple injection strategies.
 *
 * Evaluates strategies in order and combines their results.
 * Tool additions are accumulated, tool removals are accumulated.
 *
 * @param strategies The strategies to combine, evaluated in order
 */
class CompositeToolInjectionStrategy(
    private val strategies: List<ToolInjectionStrategy>,
) : ToolInjectionStrategy {

    constructor(vararg strategies: ToolInjectionStrategy) : this(strategies.toList())

    override fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
        val allToAdd = mutableListOf<com.embabel.agent.api.tool.Tool>()
        val allToRemove = mutableListOf<com.embabel.agent.api.tool.Tool>()

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
         * Create a strategy that includes MatryoshkaTool support plus custom strategies.
         */
        @JvmStatic
        fun withMatryoshka(vararg additionalStrategies: ToolInjectionStrategy): CompositeToolInjectionStrategy {
            return CompositeToolInjectionStrategy(
                listOf(MatryoshkaToolInjectionStrategy.INSTANCE) + additionalStrategies.toList()
            )
        }
    }
}
