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
package com.embabel.agent.spi.support

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.StringTransformer
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Decorate tools with metadata and publish events.
 */
class DefaultToolDecorator(
    private val toolGroupResolver: ToolGroupResolver? = null,
    private val observationRegistry: ObservationRegistry? = null,
    private val outputTransformer: StringTransformer = StringTransformer.IDENTITY,
) : ToolDecorator {

    override fun decorate(
        tool: ToolCallback,
        agentProcess: AgentProcess,
        action: Action?,
        llmOptions: LlmOptions,
    ): ToolCallback {
        val toolGroup = toolGroupResolver?.findToolGroupForTool(toolName = tool.toolDefinition.name())
        return ExceptionSuppressingToolCallback(
            delegate = OutputTransformingToolCallback(
                delegate = ObservabilityToolCallback(
                    delegate = MetadataEnrichedToolCallback(
                        toolGroupMetadata = toolGroup?.resolvedToolGroup?.metadata,
                        delegate = tool,
                    )
                        .withEventPublication(
                            agentProcess = agentProcess,
                            action = action,
                            llmOptions = llmOptions,
                        ),
                    observationRegistry = observationRegistry,
                ),
                outputTransformer = outputTransformer
            )
        )
    }
}

/**
 * Explain exception rather than propagate it
 */
class ExceptionSuppressingToolCallback(
    private val delegate: ToolCallback,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun call(toolInput: String): String {
        return try {
            delegate.call(toolInput)
        } catch (t: Throwable) {
            // Suppress the exception and return a message instead
            "WARNING: Tool '${delegate.toolDefinition.name()}' failed with exception: ${t.message ?: "No message"}"
        }
    }
}
