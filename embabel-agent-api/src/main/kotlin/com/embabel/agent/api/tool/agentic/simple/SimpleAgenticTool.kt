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
package com.embabel.agent.api.tool.agentic.simple

import com.embabel.agent.api.tool.ArtifactSinkingTool
import com.embabel.agent.api.tool.ListSink
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.agentic.AgenticSystemPromptCreator
import com.embabel.agent.api.tool.agentic.AgenticTool
import com.embabel.agent.api.tool.agentic.AgenticToolSupport
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor

/**
 * A simple agentic tool where all sub-tools are available immediately.
 *
 * This is the most basic form of [AgenticTool] - all tools are available
 * to the LLM from the start with no conditions or state requirements.
 *
 * For more controlled tool availability, see:
 * - [com.embabel.agent.api.tool.agentic.playbook.PlaybookTool]: Progressive unlock via conditions
 * - [com.embabel.agent.api.tool.agentic.state.StateMachineTool]: State-based availability
 *
 * @param definition Tool definition (name, description, input schema)
 * @param metadata Optional tool metadata
 * @param llm LLM to use for orchestration
 * @param tools Sub-tools available for the LLM to orchestrate
 * @param systemPromptCreator Create prompt for the LLM to use
 * @param maxIterations Maximum number of tool loop iterations
 * @param captureNestedArtifacts Whether to capture artifacts from nested AgenticTools
 */
data class SimpleAgenticTool(
    override val definition: Tool.Definition,
    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    override val llm: LlmOptions = LlmOptions(),
    val tools: List<Tool> = emptyList(),
    val systemPromptCreator: AgenticSystemPromptCreator = { AgenticTool.defaultSystemPrompt(definition.description) },
    override val maxIterations: Int = AgenticTool.DEFAULT_MAX_ITERATIONS,
    val captureNestedArtifacts: Boolean = false,
) : AgenticTool {

    /**
     * Create a simple agentic tool with name and description.
     */
    constructor(
        name: String,
        description: String,
    ) : this(
        definition = Tool.Definition(
            name = name,
            description = description,
            inputSchema = Tool.InputSchema.empty(),
        ),
    )

    override fun call(input: String): Tool.Result {
        if (tools.isEmpty()) {
            loggerFor<SimpleAgenticTool>().warn(
                "No tools available for SimpleAgenticTool '{}'",
                definition.name,
            )
            return Tool.Result.error("No tools available for SimpleAgenticTool")
        }

        val (agentProcess, errorResult) = AgenticToolSupport.getAgentProcessOrError(
            definition.name,
            loggerFor<SimpleAgenticTool>(),
        )
        if (errorResult != null) return errorResult

        val systemPrompt = systemPromptCreator(agentProcess!!)
        loggerFor<SimpleAgenticTool>().info(
            "Executing SimpleAgenticTool '{}' with {} tools",
            definition.name,
            tools.size,
        )

        // Wrap tools to capture any artifacts they produce
        val artifacts = mutableListOf<Any>()
        val sink = ListSink(artifacts)
        val wrappedTools = tools.map { tool ->
            // Skip wrapping nested AgenticTools if captureNestedArtifacts is false
            if (!captureNestedArtifacts && tool is AgenticTool) {
                tool
            } else {
                ArtifactSinkingTool(tool, Any::class.java, sink)
            }
        }

        val ai = executingOperationContextFor(agentProcess).ai()
        val output = ai
            .withLlm(llm)
            .withId("simple-agentic-tool-${definition.name}")
            .withTools(wrappedTools)
            .withSystemPrompt(systemPrompt)
            .generateText(input)

        return AgenticToolSupport.createResult(output, artifacts)
    }

    override fun withLlm(llm: LlmOptions): SimpleAgenticTool = copy(llm = llm)

    override fun withSystemPrompt(prompt: String): SimpleAgenticTool = copy(
        systemPromptCreator = { prompt },
    )

    override fun withMaxIterations(maxIterations: Int): SimpleAgenticTool = copy(
        maxIterations = maxIterations,
    )

    override fun withParameter(parameter: Tool.Parameter): SimpleAgenticTool = copy(
        definition = definition.withParameter(parameter),
    )

    override fun withToolObject(toolObject: Any): SimpleAgenticTool {
        val additionalTools = Tool.safelyFromInstance(toolObject)
        return if (additionalTools.isEmpty()) {
            this
        } else {
            copy(tools = tools + additionalTools)
        }
    }

    /**
     * Create a copy with a custom system prompt creator.
     */
    fun withSystemPromptCreator(promptCreator: AgenticSystemPromptCreator): SimpleAgenticTool = copy(
        systemPromptCreator = promptCreator,
    )

    /**
     * Create a copy with additional tools.
     */
    fun withTools(vararg additionalTools: Tool): SimpleAgenticTool = copy(
        tools = tools + additionalTools,
    )

    /**
     * Create a copy with tools extracted from multiple objects with @LlmTool methods.
     */
    fun withToolObjects(vararg toolObjects: Any): SimpleAgenticTool {
        val additionalTools = toolObjects.flatMap { Tool.safelyFromInstance(it) }
        return if (additionalTools.isEmpty()) {
            this
        } else {
            copy(tools = tools + additionalTools)
        }
    }

    /**
     * Create a copy with different captureNestedArtifacts setting.
     */
    fun withCaptureNestedArtifacts(capture: Boolean): SimpleAgenticTool = copy(
        captureNestedArtifacts = capture,
    )
}
