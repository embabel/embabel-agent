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
 * Injection strategy that handles [UnfoldingTool] invocations.
 *
 * When an UnfoldingTool is invoked:
 * 1. Its selected inner tools are added to the available tools
 * 2. If [com.embabel.agent.api.annotation.UnfoldingTools.removeOnInvoke] is true, the facade is removed
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
 * @see UnfoldingTool
 */
class UnfoldingToolInjectionStrategy : ToolInjectionStrategy {

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
            return ToolInjectionResult.noChange()
        }

        logger.debug(
            "UnfoldingTool '{}' exposing {} tools: {}",
            invokedTool.definition.name,
            selectedTools.size,
            selectedTools.map { it.definition.name }
        )

        // Always replace the parent with sub-tools + a guide tool that has the
        // same name as the parent. If the LLM calls the parent name again on a
        // subsequent turn (a common tool-calling mistake), the guide lists the
        // available sub-tools instead of throwing ToolNotFoundException.
        val guide = createGuideTool(invokedTool, selectedTools)
        return ToolInjectionResult.replace(wrappedTool, selectedTools + guide)
    }

    /**
     * Creates a guide tool with the same name as the parent.
     * When called, it lists the available sub-tools and their descriptions,
     * steering the LLM to call the correct one on the next turn.
     * Prevents ToolNotFoundException loops when the LLM re-calls the parent.
     */
    private fun createGuideTool(parent: UnfoldingTool, childTools: List<Tool>): Tool {
        val parentName = parent.definition.name
        val childNames = childTools.map { it.definition.name }
        val usageNotes = parent.childToolUsageNotes

        return Tool.of(
            name = parentName,
            description = "Call a specific tool instead: ${childNames.joinToString(", ")}",
        ) {
            val details = buildString {
                append("Use one of these tools directly:\n")
                childTools.forEach { tool ->
                    append("- ${tool.definition.name}: ${tool.definition.description}\n")
                }
                if (!usageNotes.isNullOrBlank()) {
                    append("\n$usageNotes")
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
        val INSTANCE = UnfoldingToolInjectionStrategy()
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
