package com.embabel.agent.spi.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.StringTransformer
import io.micrometer.observation.ObservationRegistry

/**
 * Decorate tools with metadata and publish events.
 */
class DefaultToolDecorator(
    private val toolGroupResolver: ToolGroupResolver? = null,
    private val observationRegistry: ObservationRegistry? = null,
    private val outputTransformer: StringTransformer = StringTransformer.Companion.IDENTITY,
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
                        delegate = MetadataEnrichingTool(
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