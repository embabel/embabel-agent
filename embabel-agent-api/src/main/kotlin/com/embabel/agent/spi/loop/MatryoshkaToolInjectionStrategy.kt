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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.support.DelegatingTool
import com.embabel.agent.spi.support.unwrapAs
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
 * This strategy can be combined with other strategies using [ChainedToolInjectionStrategy].
 *
 * @see MatryoshkaTool
 */
class MatryoshkaToolInjectionStrategy : ToolInjectionStrategy {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
        // Find the invoked tool (may be wrapped in decorators)
        val wrappedTool = context.currentTools.find {
            it.definition.name == context.lastToolCall.toolName
        }
        if (wrappedTool == null) {
            logger.debug("Tool '{}' not found in current tools: {}",
                context.lastToolCall.toolName,
                context.currentTools.map { it.definition.name })
            return ToolInjectionResult.noChange()
        }

        logger.debug("Found tool '{}' of type {}, attempting unwrap",
            wrappedTool.definition.name, wrappedTool::class.simpleName)

        // Unwrap to find underlying MatryoshkaTool (handles decorator wrappers)
        val invokedTool = wrappedTool.unwrapAs<MatryoshkaTool>()
        if (invokedTool == null) {
            logger.debug("Tool '{}' is not a MatryoshkaTool after unwrap. Chain: {}",
                wrappedTool.definition.name, getUnwrapChain(wrappedTool))
            return ToolInjectionResult.noChange()
        }

        logger.debug("Successfully unwrapped '{}' to MatryoshkaTool", invokedTool.definition.name)

        // Select tools based on input
        val selectedTools = invokedTool.selectTools(context.lastToolCall.toolInput)

        if (selectedTools.isEmpty()) {
            logger.warn(
                "MatryoshkaTool '{}' selected no inner tools for input: {}",
                invokedTool.definition.name,
                context.lastToolCall.toolInput
            )
            // Still remove the facade if configured, even with no tools selected
            // Note: remove the wrappedTool (which may have decorators), not the unwrapped invokedTool
            return if (invokedTool.removeOnInvoke) {
                ToolInjectionResult.remove(listOf(wrappedTool))
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

        // Note: remove the wrappedTool (which may have decorators), not the unwrapped invokedTool
        return if (invokedTool.removeOnInvoke) {
            ToolInjectionResult.replace(wrappedTool, selectedTools)
        } else {
            ToolInjectionResult.add(selectedTools)
        }
    }

    /**
     * Build a string showing the decorator chain for debugging.
     */
    private fun getUnwrapChain(tool: Tool): String {
        val chain = mutableListOf<String>()
        var current: Tool = tool
        while (true) {
            chain.add(current::class.simpleName ?: "Unknown")
            if (current is DelegatingTool) {
                current = current.delegate
            } else {
                break
            }
        }
        return chain.joinToString(" -> ")
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
 * @deprecated Use [ChainedToolInjectionStrategy] instead.
 */
@Deprecated(
    message = "Renamed to ChainedToolInjectionStrategy",
    replaceWith = ReplaceWith("ChainedToolInjectionStrategy")
)
typealias CompositeToolInjectionStrategy = ChainedToolInjectionStrategy
