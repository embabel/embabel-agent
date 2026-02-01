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
package com.embabel.agent.api.tool.playbook

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor
import org.jetbrains.annotations.ApiStatus

/**
 * Create a system prompt given the current [AgentProcess] as context.
 */
typealias PlaybookSystemPromptCreator = (AgentProcess) -> String

/**
 * A tool with conditional tool unlocking that uses an LLM to orchestrate sub-tools.
 *
 * Unlike [com.embabel.agent.api.tool.AgenticTool] which makes all tools available immediately,
 * a PlaybookTool allows tools to be progressively unlocked based on conditions such as:
 * - Prerequisites: unlock after other tools have been called
 * - Artifacts: unlock when certain artifact types are produced
 * - Custom predicates: unlock based on arbitrary conditions
 *
 * This provides more predictable LLM behavior by guiding it through a structured
 * sequence of available tools.
 *
 * ## Usage
 *
 * ```kotlin
 * // Kotlin curried syntax
 * PlaybookTool("researcher", "Research and analyze topics")
 *     .withTools(searchTool, fetchTool)           // always available
 *     .withTool(analyzeTool)(searchTool)          // unlocks after search
 *     .withTool(summarizeTool)(analyzeTool)       // unlocks after analyze
 *
 * // Java fluent syntax
 * new PlaybookTool("researcher", "Research and analyze topics")
 *     .withTools(searchTool, fetchTool)
 *     .withTool(analyzeTool).unlockedBy(searchTool)
 *     .withTool(summarizeTool).unlockedBy(analyzeTool);
 * ```
 *
 * @param definition Tool definition (name, description, input schema)
 * @param metadata Optional tool metadata
 * @param llm LLM to use for orchestration
 * @param unlockedTools Tools that are always available
 * @param lockedTools Tools with unlock conditions
 * @param systemPromptCreator Create prompt for the LLM to use
 * @param maxIterations Maximum number of tool loop iterations (default 20)
 */
@ApiStatus.Experimental
data class PlaybookTool internal constructor(
    override val definition: Tool.Definition,
    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    val llm: LlmOptions = LlmOptions(),
    internal val unlockedTools: List<Tool> = emptyList(),
    internal val lockedTools: List<LockedTool> = emptyList(),
    val systemPromptCreator: PlaybookSystemPromptCreator = { defaultSystemPrompt(definition.description) },
    val maxIterations: Int = 20,
) : Tool {

    /**
     * A tool with its unlock condition.
     */
    internal data class LockedTool(
        val tool: Tool,
        val condition: UnlockCondition,
    )

    /**
     * Number of always-unlocked tools.
     */
    val unlockedToolCount: Int get() = unlockedTools.size

    /**
     * Number of conditionally-locked tools.
     */
    val lockedToolCount: Int get() = lockedTools.size

    /**
     * Create a playbook tool with the given name and description.
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
        val allTools = unlockedTools + lockedTools.map { it.tool }
        if (allTools.isEmpty()) {
            loggerFor<PlaybookTool>().warn(
                "No tools available for PlaybookTool '{}'",
                definition.name,
            )
            return Tool.Result.error("No tools available for PlaybookTool")
        }

        val agentProcess = AgentProcess.get()
            ?: run {
                loggerFor<PlaybookTool>().error(
                    "No AgentProcess context available for PlaybookTool '{}'",
                    definition.name,
                )
                return Tool.Result.error("No AgentProcess context available for PlaybookTool")
            }

        val systemPrompt = systemPromptCreator(agentProcess)
        loggerFor<PlaybookTool>().info(
            "Executing PlaybookTool '{}' with {} unlocked tools and {} locked tools",
            definition.name,
            unlockedTools.size,
            lockedTools.size,
        )

        // Create shared state for tracking tool calls and artifacts
        val state = PlaybookState()

        // Wrap unlocked tools to track state
        val wrappedUnlockedTools = unlockedTools.map { tool ->
            StateTrackingTool(tool, state)
        }

        // Wrap locked tools with conditional execution
        val wrappedLockedTools = lockedTools.map { lockedTool ->
            ConditionalTool(lockedTool.tool, lockedTool.condition, state)
        }

        val allWrappedTools = wrappedUnlockedTools + wrappedLockedTools

        val ai = executingOperationContextFor(agentProcess).ai()
        val output = ai
            .withLlm(llm)
            .withId("playbook-tool-${definition.name}")
            .withTools(allWrappedTools)
            .withSystemPrompt(systemPrompt)
            .generateText(input)

        // Return with artifact(s) if any were captured
        val artifacts = state.artifacts
        return when (artifacts.size) {
            0 -> Tool.Result.text(output)
            1 -> Tool.Result.withArtifact(output, artifacts.single())
            else -> Tool.Result.withArtifact(output, artifacts.toList())
        }
    }

    /**
     * Add tools that are always available (no unlock conditions).
     */
    fun withTools(vararg tools: Tool): PlaybookTool = copy(
        unlockedTools = unlockedTools + tools.toList(),
    )

    /**
     * Begin registration of a tool with unlock conditions.
     * Returns a [ToolRegistration] that can be used with curried syntax or fluent API.
     *
     * ```kotlin
     * // Kotlin curried
     * .withTool(analyzeTool)(searchTool)
     *
     * // Java fluent
     * .withTool(analyzeTool).unlockedBy(searchTool)
     * ```
     */
    fun withTool(tool: Tool): ToolRegistration = ToolRegistration(tool, this)

    /**
     * Internal method to add a locked tool with its condition.
     */
    internal fun addLockedTool(tool: Tool, condition: UnlockCondition): PlaybookTool = copy(
        lockedTools = lockedTools + LockedTool(tool, condition),
    )

    /**
     * Create a copy with a different LLM.
     */
    fun withLlm(llm: LlmOptions): PlaybookTool = copy(llm = llm)

    /**
     * Create a copy with a fixed system prompt.
     */
    fun withSystemPrompt(prompt: String): PlaybookTool = copy(
        systemPromptCreator = { prompt },
    )

    /**
     * Create a copy with a custom system prompt creator.
     */
    fun withSystemPromptCreator(promptCreator: PlaybookSystemPromptCreator): PlaybookTool = copy(
        systemPromptCreator = promptCreator,
    )

    /**
     * Create a copy with a different max iterations limit.
     */
    fun withMaxIterations(maxIterations: Int): PlaybookTool = copy(
        maxIterations = maxIterations,
    )

    /**
     * Create a copy with a parameter added to the definition.
     */
    fun withParameter(parameter: Tool.Parameter): PlaybookTool = copy(
        definition = definition.withParameter(parameter),
    )

    /**
     * Create a copy with tools extracted from an object with @LlmTool methods.
     * These tools are added as always-unlocked.
     */
    fun withToolObject(toolObject: Any): PlaybookTool {
        val additionalTools = Tool.safelyFromInstance(toolObject)
        return if (additionalTools.isEmpty()) {
            this
        } else {
            copy(unlockedTools = unlockedTools + additionalTools)
        }
    }

    companion object {

        fun defaultSystemPrompt(description: String) = """
            You are an intelligent agent that can use tools to help you complete tasks.
            Use the provided tools to perform the following task:
            $description

            Note: Some tools may require certain prerequisites before they become available.
            If a tool indicates it is not yet available, use the required prerequisite tools first.
            """.trimIndent()
    }
}
