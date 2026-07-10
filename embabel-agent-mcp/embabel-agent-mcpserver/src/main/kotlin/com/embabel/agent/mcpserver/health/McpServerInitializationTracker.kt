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
package com.embabel.agent.mcpserver.health

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks MCP server initialization for health / readiness checks.
 *
 * Updated by [com.embabel.agent.mcpserver.AbstractMcpServerConfiguration] as the
 * async exposure pipeline starts, succeeds, or fails.
 */
class McpServerInitializationTracker {

    private val stateRef = AtomicReference(McpServerInitializationState.NOT_STARTED)
    private val issueList = CopyOnWriteArrayList<String>()

    val state: McpServerInitializationState
        get() = stateRef.get()

    val issues: List<String>
        get() = issueList.toList()

    fun markInitializing() {
        issueList.clear()
        stateRef.set(McpServerInitializationState.INITIALIZING)
    }

    fun markReady() {
        stateRef.set(McpServerInitializationState.READY)
    }

    fun markFailed(issue: String) {
        issueList.add(issue)
        stateRef.set(McpServerInitializationState.FAILED)
    }
}
