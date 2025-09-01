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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.core.AgentProcess
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Bind AgentProcess to ToolContext for use in tool callbacks.
 */
class AgentProcessBindingToolCallback(
    private val delegate: ToolCallback,
    private val agentProcess: AgentProcess,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun call(toolInput: String): String {
        val previousValue = AgentProcess.get()
        try {
            AgentProcess.set(agentProcess)
            return delegate.call(toolInput)
        } finally {
            // Restore previous value (or remove if it was null)
            if (previousValue != null) {
                AgentProcess.set(previousValue)
            } else {
                AgentProcess.remove()
            }
        }
    }
}
