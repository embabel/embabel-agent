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
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.ToolDecorator

/**
 * Utility for resolving ToolGroups and decorating tools.
 * Provides consistent tool resolution across streaming, thinking, and standard LLM operations.
 */
object ToolResolutionHelper {

    /**
     * Resolves ToolGroups from the interaction and decorates all tools.
     *
     * @param interaction LLM interaction containing tools and tool groups
     * @param agentProcess Process context for accessing toolGroupResolver
     * @param action Optional action context for decoration
     * @param toolDecorator Decorator to make tools platform-aware
     * @return List of resolved and decorated tools
     */
    fun resolveAndDecorate(
        interaction: LlmInteraction,
        agentProcess: AgentProcess?,
        action: Action?,
        toolDecorator: ToolDecorator,
    ): List<Tool> {
        if (agentProcess == null) {
            return interaction.tools
        }
        val toolGroupResolver = agentProcess.processContext.platformServices.agentPlatform.toolGroupResolver
        val resolvedTools = interaction.resolveTools(toolGroupResolver)
        return resolvedTools.map { tool ->
            toolDecorator.decorate(tool, agentProcess, action, interaction.llm)
        }
    }
}
