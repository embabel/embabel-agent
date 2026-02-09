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
package com.embabel.agent.api.tool.progressive

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess

/**
 * A [ProgressiveTool] with a fixed set of inner tools that are revealed
 * when invoked, regardless of the agent process context.
 *
 * This pattern is useful for:
 * - Reducing tool set complexity for the LLM
 * - Grouping related tools under a category facade
 * - Progressive disclosure based on LLM intent
 *
 * ## Context Preservation
 *
 * When an UnfoldingTool is expanded, a context tool can be created that
 * preserves the parent's description and optional usage notes. This solves
 * the problem where child tools would lose context about the parent's purpose.
 *
 * The [childToolUsageNotes] field provides additional guidance on how to
 * use the child tools, included only once in the context tool rather than
 * duplicated in each child tool's description.
 *
 * @see ProgressiveTool for context-dependent tool revelation
 */
interface UnfoldingTool : ProgressiveTool {

    /**
     * The inner tools that will be exposed when this tool is invoked.
     * This is a fixed set that does not vary by context.
     */
    val innerTools: List<Tool>

    /**
     * Returns the fixed [innerTools] regardless of process context.
     */
    override fun innerTools(process: AgentProcess): List<Tool> = innerTools

    /**
     * Optional usage notes to guide the LLM on when to invoke the child tools.
     */
    val childToolUsageNotes: String? get() = null

    /**
     * Whether to remove this tool after invocation.
     *
     * When `true` (default), the facade is replaced by its contents.
     * When `false`, the facade remains available for re-invocation
     * (useful for category-based selection with different arguments).
     */
    val removeOnInvoke: Boolean get() = true

    /**
     * Select which inner tools to expose based on invocation input.
     *
     * Override this method to implement category-based or argument-driven
     * tool selection. Default implementation returns all inner tools.
     *
     * @param input The JSON input string provided to this tool
     * @return The tools to expose (subset of [innerTools] or all)
     */
    fun selectTools(input: String): List<Tool> = innerTools
}
