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
import com.embabel.chat.Message

/**
 * Result of tool injection evaluation.
 *
 * Supports both adding new tools and removing existing ones, enabling
 * patterns like MatryoshkaTool where a facade tool is replaced by its inner tools.
 */
data class ToolInjectionResult(
    val toolsToAdd: List<Tool> = emptyList(),
    val toolsToRemove: List<Tool> = emptyList(),
) {
    companion object {
        private val NO_CHANGE = ToolInjectionResult()

        /**
         * No tools to add or remove.
         */
        @JvmStatic
        fun noChange(): ToolInjectionResult = NO_CHANGE

        /**
         * Add tools without removing any.
         */
        @JvmStatic
        fun add(tools: List<Tool>): ToolInjectionResult =
            if (tools.isEmpty()) NO_CHANGE else ToolInjectionResult(toolsToAdd = tools)

        /**
         * Add a single tool.
         */
        @JvmStatic
        fun add(tool: Tool): ToolInjectionResult = ToolInjectionResult(toolsToAdd = listOf(tool))

        /**
         * Replace a tool with others (remove one, add many).
         */
        @JvmStatic
        fun replace(remove: Tool, add: List<Tool>): ToolInjectionResult =
            ToolInjectionResult(toolsToRemove = listOf(remove), toolsToAdd = add)

        /**
         * Remove tools without adding any.
         */
        @JvmStatic
        fun remove(tools: List<Tool>): ToolInjectionResult =
            if (tools.isEmpty()) NO_CHANGE else ToolInjectionResult(toolsToRemove = tools)
    }

    /**
     * Whether this result represents any changes.
     */
    fun hasChanges(): Boolean = toolsToAdd.isNotEmpty() || toolsToRemove.isNotEmpty()
}

/**
 * Strategy for dynamically injecting tools during a conversation.
 *
 * Implementations examine tool call results and conversation state
 * to determine if tools should be added or removed.
 *
 * This interface is designed for extensibility. Strategies include:
 * - Conditional unlocks based on agent performance
 * - Phase-based tools (planning vs execution)
 * - Skill acquisition patterns
 * - MatryoshkaTool progressive disclosure
 *
 * ## Migration Guide
 * New implementations should override [evaluate] instead of [evaluateToolResult].
 * The [evaluate] method supports both adding and removing tools.
 */
interface ToolInjectionStrategy {

    /**
     * Called after each tool execution to determine tool changes.
     *
     * Default implementation bridges to legacy [evaluateToolResult] for backward compatibility.
     * Override this method for new implementations that need to add/remove tools.
     *
     * @param context The current state of the tool loop
     * @return Result containing tools to add and/or remove
     */
    fun evaluate(context: ToolInjectionContext): ToolInjectionResult {
        // Bridge to legacy method for backward compatibility
        @Suppress("DEPRECATION")
        val toolsToAdd = evaluateToolResult(context)
        return ToolInjectionResult.add(toolsToAdd)
    }

    /**
     * Legacy method for backward compatibility.
     * Override [evaluate] instead for new implementations.
     *
     * @param context The current state of the tool loop
     * @return Tools to add, or empty list if none
     */
    @Deprecated(
        message = "Use evaluate() which supports both adding and removing tools",
        replaceWith = ReplaceWith("evaluate(context)")
    )
    fun evaluateToolResult(context: ToolInjectionContext): List<Tool> = emptyList()

    companion object {
        /**
         * A no-op strategy that never changes tools.
         */
        val NONE: ToolInjectionStrategy = object : ToolInjectionStrategy {
            override fun evaluate(context: ToolInjectionContext): ToolInjectionResult =
                ToolInjectionResult.noChange()

            @Deprecated("Use evaluate()")
            override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> = emptyList()
        }

        /**
         * The default strategy that handles common patterns automatically.
         *
         * Uses [ChainedToolInjectionStrategy] to combine built-in strategies:
         * - [MatryoshkaTool] progressive disclosure
         *
         * Use this instead of [NONE] to get automatic support for built-in tool patterns
         * without needing to know about specific strategy implementations.
         */
        val DEFAULT: ToolInjectionStrategy by lazy {
            ChainedToolInjectionStrategy.withMatryoshka()
        }
    }
}

/**
 * Context provided to injection strategies for decision-making.
 */
data class ToolInjectionContext(
    val conversationHistory: List<Message>,
    val currentTools: List<Tool>,
    val lastToolCall: ToolCallResult,
    val iterationCount: Int,
)

/**
 * Result of a tool call execution.
 */
data class ToolCallResult(
    val toolName: String,
    val toolInput: String,
    val result: String,
    val resultObject: Any?,
)
