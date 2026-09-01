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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ToolConsumer
import com.embabel.agent.core.ToolNamingStrategy
import com.embabel.agent.spi.ToolGroupResolver

/**
 * Applies the platform tool naming policy only while an LLM resolves its tools.
 */
internal class ToolNamingContext(
    private val delegate: ToolConsumer,
    private val toolNamingStrategy: ToolNamingStrategy,
    private val ownerName: String,
) : ToolConsumer by delegate {


    override fun resolveTools(toolGroupResolver: ToolGroupResolver): List<Tool> =
        ToolConsumer.resolveTools(delegate, toolGroupResolver, ::name)

    fun name(tool: Tool): Tool {
        val qualifiedName = toolNamingStrategy.nameFor(tool, ownerName)
        return if (qualifiedName == tool.definition.name) tool else tool.withName(qualifiedName)
    }

    companion object {

        fun forLlmCall(
            toolConsumer: ToolConsumer,
            agentProcess: AgentProcess,
            action: Action?,
        ): ToolNamingContext {
            val strategy = runCatching {
                agentProcess.processContext.platformServices.toolNamingStrategy()
            }.getOrDefault(ToolNamingStrategy.LEGACY_NAME_ONLY)
            val agentName = agentProcess.agent.name
            val actionName = action?.name?.takeIf { it.isNotBlank() }
            val ownerName = when {
                actionName == null -> agentName
                actionName == agentName || actionName.startsWith("$agentName.") -> actionName
                else -> "$agentName.$actionName"
            }
            return ToolNamingContext(
                delegate = toolConsumer,
                toolNamingStrategy = strategy,
                ownerName = ownerName,
            )
        }
    }
}
