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

import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
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
            logger.debug(
                "Tool '{}' not found in current tools: {}",
                context.lastToolCall.toolName,
                context.currentTools.map { it.definition.name })
            return ToolInjectionResult.noChange()
        }

        logger.debug(
            "Found tool '{}' of type {}, attempting unwrap",
            wrappedTool.definition.name, wrappedTool::class.simpleName
        )

        // Unwrap to find underlying UnfoldingTool (handles decorator wrappers)
        val invokedTool = wrappedTool.unwrapAs<UnfoldingTool>()
        if (invokedTool == null) {
            logger.debug(
                "Tool '{}' is not an UnfoldingTool after unwrap. Chain: {}",
                wrappedTool.definition.name, getUnwrapChain(wrappedTool)
            )
            return ToolInjectionResult.noChange()
        }

        logger.debug("Successfully unwrapped '{}' to UnfoldingTool", invokedTool.definition.name)

        // Select tools based on input
        val selectedTools = invokedTool.selectTools(context.lastToolCall.toolInput)

        if (selectedTools.isEmpty()) {
            logger.warn(
                "UnfoldingTool '{}' selected no inner tools for input: {}",
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
            "UnfoldingTool '{}' exposing {} tools: {}",
            invokedTool.definition.name,
            selectedTools.size,
            selectedTools.map { it.definition.name }
        )

        // Create context tool to preserve parent's description for the LLM
        val contextTool = createContextTool(invokedTool, selectedTools)
        val toolsToInject = selectedTools + contextTool

        // Note: remove the wrappedTool (which may have decorators), not the unwrapped invokedTool
        return if (invokedTool.removeOnInvoke) {
            ToolInjectionResult.replace(wrappedTool, toolsToInject)
        } else {
            ToolInjectionResult.add(toolsToInject)
        }
    }

    /**
     * Creates a context tool that preserves the parent UnfoldingTool's description
     * and provides information about the available child tools.
     *
     * This solves the problem where child tools would lose context about the parent's purpose.
     * For example, a "spotify_search" tool containing vector_search, text_search, etc.
     * Without the context tool, the LLM only sees generic search tools.
     * With the context tool, it also sees "spotify_search_context" explaining these are
     * Spotify music search tools, with optional usage notes on when to use each.
     */
    private fun createContextTool(parent: UnfoldingTool, childTools: List<Tool>): Tool {
        val parentName = parent.definition.name
        val parentDescription = parent.definition.description
        val toolNames = childTools.map { it.definition.name }
        val usageNotes = parent.childToolUsageNotes

        // Build description: parent description + tool list + optional usage notes
        val descriptionBuilder = StringBuilder()
        descriptionBuilder.append(parentDescription)
        descriptionBuilder.append(". Available: ${toolNames.joinToString(", ")}")
        if (!usageNotes.isNullOrBlank()) {
            descriptionBuilder.append(". $usageNotes")
        }

        return Tool.of(
            name = "${parentName}_context",
            description = descriptionBuilder.toString(),
        ) {
            // When called, return full details about each child tool
            val details = buildString {
                append("Tools for $parentDescription:\n")
                childTools.forEach { tool ->
                    append("- ${tool.definition.name}: ${tool.definition.description}\n")
                }
                if (!usageNotes.isNullOrBlank()) {
                    append("\nUsage notes: $usageNotes")
                }
            }
            Tool.Result.text(details.trim())
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
