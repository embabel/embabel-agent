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
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.Usage
import com.embabel.chat.Message

/**
 * Result of executing an Embabel tool loop.
 *
 * @param O The output type
 * @param result The parsed result
 * @param conversationHistory Full conversation history including tool calls
 * @param totalIterations Number of LLM inference iterations
 * @param injectedTools All tools added during the conversation via injection strategies
 * @param removedTools All tools removed during the conversation via injection strategies
 * @param totalUsage Accumulated usage across all LLM calls in the loop
 * @param replanRequested True if the loop terminated due to a tool requesting replanning
 * @param replanReason Human-readable explanation of why replan was requested
 * @param blackboardUpdater Callback to update the blackboard before replanning
 */
data class ToolLoopResult<O>(
    val result: O,
    val conversationHistory: List<Message>,
    val totalIterations: Int,
    val injectedTools: List<Tool>,
    val removedTools: List<Tool> = emptyList(),
    val totalUsage: Usage? = null,
    val replanRequested: Boolean = false,
    val replanReason: String? = null,
    val blackboardUpdater: (Blackboard) -> Unit = {},
)
