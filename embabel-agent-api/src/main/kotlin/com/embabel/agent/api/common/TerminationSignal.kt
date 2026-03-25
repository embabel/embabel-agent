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
package com.embabel.agent.api.common

/**
 * Scope of termination - whether to terminate the current action only
 * or the entire agent process.
 */
enum class TerminationScope(val value: String) {
    /**
     * Terminate the entire agent process.
     */
    AGENT("agent"),

    /**
     * Terminate the current action only, allowing agent to continue with next action.
     */
    ACTION("action"),
}

/**
 * Signal for graceful termination. When set on the agent process,
 * the agent or action will terminate at the next natural checkpoint.
 *
 * For agent termination: checked before each tick() in AbstractAgentProcess.
 * For action termination: checked between tool calls in the tool loop.
 *
 * @param scope Whether to terminate agent or action
 * @param reason Human-readable explanation for termination
 */
data class TerminationSignal(
    val scope: TerminationScope,
    val reason: String,
)
