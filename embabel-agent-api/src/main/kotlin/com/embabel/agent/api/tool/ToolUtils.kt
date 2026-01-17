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

/**
 * Utility functions for working with tools.
 */
object ToolUtils {

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
