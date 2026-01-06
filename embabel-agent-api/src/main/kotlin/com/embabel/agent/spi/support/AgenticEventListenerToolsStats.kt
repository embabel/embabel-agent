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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.ToolStats
import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.ToolCallResponseEvent

class AgenticEventListenerToolsStats : AgenticEventListener, ToolsStats {

    private val _stats: MutableMap<String, ToolStats> = mutableMapOf()

    override val toolsStats: Map<String, ToolStats>
        get() = _stats.toSortedMap()

    private fun record(e: ToolCallResponseEvent) {
        val existing = _stats[e.request.tool]
        if (existing != null) {
            _stats[e.request.tool] = ToolStats(
                name = e.request.tool,
                calls = existing.calls + 1,
                failures = existing.failures + if (e.result.isFailure) 1 else 0,
                averageResponseTime = (existing.averageResponseTime * existing.calls + e.runningTime.toMillis()) / (existing.calls + 1)
            )
        } else {
            _stats[e.request.tool] = ToolStats(
                name = e.request.tool,
                calls = 1,
                averageResponseTime = e.runningTime.toMillis(),
                failures = if (e.result.isFailure) 1 else 0
            )
        }
    }

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event is ToolCallResponseEvent) {
            record(event)
        }
    }
}
