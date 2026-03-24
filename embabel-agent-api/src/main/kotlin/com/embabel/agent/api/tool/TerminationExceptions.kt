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
 * Exception thrown to immediately terminate the entire agent process.
 *
 * When thrown from a tool, this exception propagates through the tool loop
 * and action executor, causing the agent process to terminate immediately.
 *
 * Use this for immediate termination. For graceful termination that waits
 * for natural checkpoints, use [com.embabel.agent.api.common.TerminationSignal]
 * via the ProcessContext API.
 *
 * Example usage:
 * ```kotlin
 * @LlmTool(description = "Stops the agent when critical error detected")
 * fun criticalStop(reason: String): String {
 *     throw TerminateAgentException("Critical error: $reason")
 * }
 * ```
 *
 * @param reason Human-readable explanation for termination
 */
class TerminateAgentException(
    val reason: String,
) : RuntimeException(reason), ToolControlFlowSignal

/**
 * Exception thrown to immediately terminate the current action only.
 *
 * When thrown from an action or tool, this exception terminates the current
 * action's tool loop but allows the agent to continue with the next planned action.
 *
 * Use this for immediate action termination. For graceful termination that
 * waits for natural checkpoints, use [com.embabel.agent.api.common.TerminationSignal]
 * via the ProcessContext API.
 *
 * Example usage:
 * ```kotlin
 * @Action
 * fun processStep(context: ActionContext): String {
 *     if (shouldSkip()) {
 *         throw TerminateActionException("Skipping: condition met")
 *     }
 *     // ... normal processing
 * }
 * ```
 *
 * @param reason Human-readable explanation for termination
 */
class TerminateActionException(
    val reason: String,
) : RuntimeException(reason), ToolControlFlowSignal
