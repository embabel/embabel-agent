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
package com.embabel.agent.api.tool.callback

import com.embabel.agent.api.tool.Tool
import com.embabel.chat.ToolCall

/**
 * Read-only observer for individual tool call events.
 *
 * Provides observation of tool execution without access to conversation history
 * or iteration state. Works in both streaming mode (where the framework manages
 * the tool loop internally) and non-streaming mode (as a lightweight alternative
 * to [ToolLoopInspector] when history/iteration context is not needed).
 *
 * @see ToolLoopInspector for tool loop-level inspection with full conversation context
 */
interface ToolCallInspector {

    /**
     * Called before tool execution starts.
     * Default no-op.
     */
    fun beforeToolCall(context: BeforeToolCallContext) = Unit

    /**
     * Called after tool execution completes (success or failure).
     * Default no-op.
     */
    fun afterToolCall(context: AfterToolCallContext) = Unit
}

/**
 * Context provided before tool execution.
 *
 * Lightweight context without conversation history or iteration state.
 * Used in both streaming and non-streaming modes.
 *
 * @property toolCall The tool call about to be executed
 */
data class BeforeToolCallContext(
    val toolCall: ToolCall,
)

/**
 * Context provided after tool execution.
 *
 * Lightweight context without conversation history or iteration state.
 * Used in both streaming and non-streaming modes.
 *
 * Implements [ToolResultContext] for shared access to tool result fields.
 * Check [result] type to determine success ([Tool.Result.Text], [Tool.Result.WithArtifact])
 * or failure ([Tool.Result.Error]).
 *
 * @property toolCall The tool call that was executed
 * @property result The typed tool result (may be [Tool.Result.Error] on failure)
 * @property resultAsString String representation of the result
 * @property durationMs Execution duration in milliseconds
 */
data class AfterToolCallContext(
    override val toolCall: ToolCall,
    override val result: Tool.Result,
    override val resultAsString: String,
    val durationMs: Long,
) : ToolResultContext
