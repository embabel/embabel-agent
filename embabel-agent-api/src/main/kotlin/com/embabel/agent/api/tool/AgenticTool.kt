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
package com.embabel.agent.api.tool

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor

/**
 * Create a system prompt given the current [AgentProcess]
 * as context.
 */
typealias SystemPromptCreator = (AgentProcess) -> String

/**
 * An agentic tool that uses an LLM to orchestrate other tools.
 *
 * Unlike a regular [Tool] which executes deterministic logic, an [AgenticTool] tool
 * uses an LLM to decide which sub-tools to call based on a prompt.
 *
 * @param definition Tool definition (name, description, input schema)
 * @param metadata Optional tool metadata
 * @param llm Llm to use for orchestration
 * @param tools Sub-tools available for the LLM to orchestrate
 * @param systemPromptCreator Create prompt for the LLM to use.
 * Specify to customize behavior
 * @param captureNestedArtifacts Whether to capture artifacts from nested AgenticTools.
 * Default is false, meaning only artifacts from leaf tools are captured.
 * Set to true to capture all artifacts including those from nested agentic sub-tools.
 */
data class AgenticTool(
    override val definition: Tool.Definition,
    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    val llm: LlmOptions = LlmOptions(),
    val tools: List<Tool> = emptyList(),
    val systemPromptCreator: SystemPromptCreator = { defaultSystemPrompt(definition.description) },
    val captureNestedArtifacts: Boolean = false,
) : Tool {

    /**
     * Create an agentic tool that will need to be customized
     * to add tools (and possibly specify an LLM) to be useful.
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
            loggerFor<AgenticTool>().warn(
                "No tools available for Agentic tool '{}'",
                definition.name,
            )
            return Tool.Result.error("No tools available for Agentic tool")
        }
        val agentProcess = AgentProcess.get()
            ?: run {
                loggerFor<AgenticTool>().error(
                    "No AgentProcess context available for Agentic tool '{}'",
                    definition.name,
                )
                return Tool.Result.error("No AgentProcess context available for Agentic tool")
            }
        val systemPrompt = systemPromptCreator(agentProcess)
        loggerFor<AgenticTool>().info(
            "Executing Agentic tool '{}' with system prompt: {}",
            definition.name,
            systemPrompt,
        )

        // Wrap tools to capture any artifacts they produce
        val artifacts = mutableListOf<Any>()
        val wrappedTools = tools.map { tool ->
            ArtifactCapturingTool(
                delegate = tool,
                artifacts = artifacts,
                captureNestedArtifacts = captureNestedArtifacts,
            )
        }

        val ai = executingOperationContextFor(agentProcess).ai()
        val output = ai
            .withLlm(llm)
            .withId("agentic-tool-${definition.name}")
            .withTools(wrappedTools)
            .withSystemPrompt(systemPrompt)
            .generateText(input)

        // Return with artifact(s) if any were captured, otherwise just text
        return when (artifacts.size) {
            0 -> Tool.Result.text(output)
            1 -> Tool.Result.withArtifact(output, artifacts.single())
            else -> Tool.Result.withArtifact(output, artifacts.toList())
        }
    }

    /**
     * Create a copy with different model.
     */
    fun withLlm(llm: LlmOptions): AgenticTool =
        copy(llm = llm)

    fun withParameter(parameter: Tool.Parameter): AgenticTool = copy(
        definition = definition.withParameter(parameter),
    )

    /**
     * Create a copy with additional tools.
     */
    fun withTools(vararg additionalTools: Tool): AgenticTool = copy(
        tools = tools + additionalTools,
    )

    /**
     * Create a copy with fixed syste prompt.
     * The system prompt describes the supervisor behavior.
     */
    fun withSystemPrompt(prompt: String): AgenticTool = copy(
        systemPromptCreator = { prompt },
    )

    fun withSystemPromptCreator(promptCreator: SystemPromptCreator): AgenticTool = copy(
        systemPromptCreator = promptCreator,
    )

    /**
     * Create a copy with different captureNestedArtifacts setting.
     * When false (default), artifacts from nested AgenticTools are not captured.
     * When true, all artifacts are captured including those from nested agentic sub-tools.
     */
    fun withCaptureNestedArtifacts(capture: Boolean): AgenticTool = copy(
        captureNestedArtifacts = capture,
    )

    /**
     * Create a copy with tools extracted from an object with @LlmTool methods.
     * If the object has no @LlmTool methods, returns this unchanged.
     */
    fun withToolObject(toolObject: Any): AgenticTool {
        val additionalTools = Tool.safelyFromInstance(toolObject)
        return if (additionalTools.isEmpty()) {
            this
        } else {
            copy(tools = tools + additionalTools)
        }
    }

    /**
     * Create a copy with tools extracted from multiple objects with @LlmTool methods.
     * Objects without @LlmTool methods are silently ignored.
     */
    fun withToolObjects(vararg toolObjects: Any): AgenticTool {
        val additionalTools = toolObjects.flatMap { Tool.safelyFromInstance(it) }
        return if (additionalTools.isEmpty()) {
            this
        } else {
            copy(tools = tools + additionalTools)
        }
    }

    companion object {

        fun defaultSystemPrompt(description: String) = """
            You are an intelligent agent that can use tools to help you complete tasks.
            Use the provided tools to perform the following task:
            $description
            """.trimIndent()
    }
}

/**
 * Tool wrapper that captures artifacts from [Tool.Result.WithArtifact] results.
 * Used internally by [AgenticTool] to collect artifacts produced by sub-tools.
 *
 * @param delegate The tool to wrap
 * @param artifacts Shared list to collect artifacts into
 * @param captureNestedArtifacts When false, artifacts from nested AgenticTools are skipped
 */
internal class ArtifactCapturingTool(
    override val delegate: Tool,
    private val artifacts: MutableList<Any>,
    private val captureNestedArtifacts: Boolean = false,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    /**
     * Check if artifact capture should be skipped for this delegate.
     * Returns true if delegate is an AgenticTool and captureNestedArtifacts is false.
     */
    fun shouldSkipCapture(): Boolean {
        return !captureNestedArtifacts && delegate is AgenticTool
    }

    override fun call(input: String): Tool.Result {
        val result = delegate.call(input)
        if (result is Tool.Result.WithArtifact && !shouldSkipCapture()) {
            artifacts.add(result.artifact)
            loggerFor<ArtifactCapturingTool>().debug(
                "Captured artifact of type {} from tool '{}'",
                result.artifact::class.simpleName,
                definition.name,
            )
        }
        return result
    }
}
