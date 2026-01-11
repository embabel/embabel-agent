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

/**
 * Thrown when the LLM requests a tool that is not available.
 */
class ToolNotFoundException(
    val requestedTool: String,
    val availableTools: List<String>,
) : RuntimeException(
    "Tool '$requestedTool' not found. Available tools: $availableTools"
)

/**
 * Thrown when the tool loop exceeds the maximum number of iterations.
 */
class MaxIterationsExceededException(
    val maxIterations: Int,
) : RuntimeException(
    "Tool loop exceeded maximum iterations ($maxIterations). " +
        "This may indicate a loop in tool calls or an overly complex task."
)
