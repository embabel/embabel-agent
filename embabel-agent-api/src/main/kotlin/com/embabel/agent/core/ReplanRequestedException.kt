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
package com.embabel.agent.core

/**
 * Exception thrown by a tool to signal that the tool loop should terminate
 * and the agent should replan based on the updated blackboard state.
 *
 * This enables tools to influence agent behavior at a higher level than just
 * returning results. Use cases include:
 * - Chat routing: A routing tool classifies user intent and requests replan
 *   to switch to the appropriate handler action
 * - Discovery: A tool discovers that the current approach won't work and
 *   the agent should try a different plan
 * - State changes: A tool detects significant state changes that require
 *   the agent to reassess its goals
 *
 * When caught by the tool loop:
 * 1. The loop terminates gracefully (no error)
 * 2. [blackboardUpdater] is made available for the caller to apply
 * 3. The caller (typically action executor) can trigger GOAP replanning
 *
 * Example usage:
 * ```kotlin
 * @LlmTool(description = "Routes user to appropriate handler")
 * fun routeUser(message: String): String {
 *     val intent = classifyIntent(message)
 *     throw ReplanRequestedException(
 *         reason = "Classified as $intent request",
 *         blackboardUpdater = { bb -> bb.addObject(intent) }
 *     )
 * }
 * ```
 *
 * @param reason Human-readable explanation of why replan is needed
 * @param blackboardUpdater Callback to update the blackboard before replanning
 */
class ReplanRequestedException(
    val reason: String,
    val blackboardUpdater: (Blackboard) -> Unit = {},
) : RuntimeException(reason)
