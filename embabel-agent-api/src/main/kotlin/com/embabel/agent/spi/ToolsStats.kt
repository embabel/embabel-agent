/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi

import com.embabel.agent.event.AgentProcessEvent
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.event.ToolCallResponseEvent

data class ToolStats(
    val name: String,
    val calls: Int
)

interface ToolStatsSource {
    val toolsStats: ToolsStats
}

class AgenticEventListenerToolStatsSource(
    override val toolsStats: ToolsStats = ToolsStats(),
) : AgenticEventListener, ToolStatsSource {
    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event is ToolCallResponseEvent) {
            toolsStats.record(event)
        }
    }
}

data class ToolsStats(
    private val _stats: MutableMap<String, ToolStats> = mutableMapOf(),
) {

    val stats: Map<String, ToolStats>
        get() = _stats.toSortedMap()

    fun record(e: ToolCallResponseEvent) {
        val existing = _stats[e.function]
        if (existing != null) {
            _stats[e.function] = existing.copy(calls = existing.calls + 1)
        } else {
            _stats[e.function] = ToolStats(name = e.function, 1)
        }
    }
}
