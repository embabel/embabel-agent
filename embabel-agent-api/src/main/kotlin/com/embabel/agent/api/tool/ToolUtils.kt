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
package com.embabel.agent.api.tool

import com.embabel.agent.spi.support.DelegatingTool

/**
 * Utility functions for working with tools.
 */
object ToolUtils {

    /**
     * Make this tool always replan before execution.
     */
    @JvmStatic
    fun replanAlways(tool: Tool): Tool {
        return ConditionalReplanningTool(tool)
        { ReplanDecision("${tool.definition.name} replans") }
    }

    /**
     * When the decider returns a [ReplanDecision], replan before execution.
     * The decider receives the artifact cast to type T and the replan context.
     * If the artifact is null or cannot be cast to T, the decider is not called.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> conditionalReplan(
        tool: Tool,
        decider: (t: T, replanContext: ReplanContext) -> ReplanDecision?,
    ): DelegatingTool {
        return ConditionalReplanningTool(tool)
        { replanContext ->
            val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
            try {
                decider(artifact as T, replanContext)
            } catch (_: ClassCastException) {
                null
            }
        }
    }

    /**
     * When the predicate matches the tool result artifact, replan.
     * The predicate receives the artifact cast to type T.
     * If the artifact is null or cannot be cast to T, returns normally.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> replanWhen(
        tool: Tool,
        predicate: (t: T) -> Boolean,
    ): DelegatingTool {
        return ConditionalReplanningTool(tool)
        { replanContext ->
            val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
            try {
                if (predicate(artifact as T)) {
                    ReplanDecision("${tool.definition.name} replans based on result")
                } else {
                    null
                }
            } catch (_: ClassCastException) {
                null
            }
        }
    }

    /**
     * Format a list of tools as an ASCII tree structure.
     * MatryoshkaTools are expanded recursively to show their inner tools.
     *
     * @param name The name to display at the root of the tree
     * @param tools The list of tools to format
     * @return A formatted tree string, or a message if no tools are present
     */
    @JvmStatic
    fun formatToolTree(name: String, tools: List<Tool>): String {
        if (tools.isEmpty()) {
            return "$name has no tools"
        }

        val sb = StringBuilder()
        sb.append(name).append("\n")
        formatToolsRecursive(sb, tools, "")
        return sb.toString().trim()
    }

    private fun formatToolsRecursive(sb: StringBuilder, tools: List<Tool>, indent: String) {
        tools.forEachIndexed { i, tool ->
            val isLast = i == tools.size - 1
            val prefix = if (isLast) "└── " else "├── "
            val childIndent = indent + if (isLast) "    " else "│   "

            if (tool is MatryoshkaTool) {
                sb.append(indent).append(prefix).append(tool.definition.name)
                    .append(" (").append(tool.innerTools.size).append(" inner tools)\n")
                formatToolsRecursive(sb, tool.innerTools, childIndent)
            } else {
                sb.append(indent).append(prefix).append(tool.definition.name).append("\n")
            }
        }
    }
}
