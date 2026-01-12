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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.agent.spi.support.*
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.StringTransformer
import io.micrometer.observation.ObservationRegistry

/**
 * Decorate tools with metadata and publish events.
 */
class DefaultToolDecorator(
    private val toolGroupResolver: ToolGroupResolver? = null,
    private val observationRegistry: ObservationRegistry? = null,
    private val outputTransformer: StringTransformer = StringTransformer.IDENTITY,
) : ToolDecorator {

    override fun decorate(
        tool: Tool,
        agentProcess: AgentProcess,
        action: Action?,
        llmOptions: LlmOptions,
    ): Tool {
        val toolGroup = toolGroupResolver?.findToolGroupForTool(toolName = tool.definition.name)
        return AgentProcessBindingTool(
            delegate = ExceptionSuppressingTool(
                delegate = OutputTransformingTool(
                    delegate = ObservabilityTool(
                        delegate = MetadataEnrichedTool(
                            delegate = tool,
                            toolGroupMetadata = toolGroup?.resolvedToolGroup?.metadata,
                        ).withEventPublication(
                            agentProcess = agentProcess,
                            action = action,
                            llmOptions = llmOptions,
                        ),
                        observationRegistry = observationRegistry,
                    ),
                    outputTransformer = outputTransformer
                )
            ),
            agentProcess = agentProcess,
        )
    }
}
