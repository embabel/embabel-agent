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
package com.embabel.agent.spi.toolloop

import com.embabel.agent.api.tool.Tool
import com.embabel.chat.Message

/**
 * Strategy for dynamically injecting tools during a conversation.
 *
 * Implementations examine tool call results and conversation state
 * to determine if additional tools should become available.
 *
 * This interface is designed for extensibility. Future strategies could include:
 * - Conditional unlocks based on agent performance
 * - Phase-based tools (planning vs execution)
 * - Skill acquisition patterns
 */
interface ToolInjectionStrategy {

    /**
     * Called after each tool execution to determine if new tools should be injected.
     *
     * @param context The current state of the tool loop
     * @return Tools to add, or empty list if none
     */
    fun evaluateToolResult(context: ToolInjectionContext): List<Tool>

    companion object {
        /**
         * A no-op strategy that never injects tools.
         */
        val NONE: ToolInjectionStrategy = object : ToolInjectionStrategy {
            override fun evaluateToolResult(context: ToolInjectionContext): List<Tool> = emptyList()
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
