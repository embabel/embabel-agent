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
package com.embabel.agent.api.tool.agentic.playbook

import com.embabel.agent.api.common.ExecutingOperationContext
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.agentic.AgenticSystemPromptCreator
import com.embabel.agent.api.tool.agentic.AgenticTool
import com.embabel.agent.api.tool.agentic.AgenticToolSupport
import com.embabel.agent.api.tool.agentic.DomainToolFactory
import com.embabel.agent.api.tool.agentic.DomainToolSource
import com.embabel.agent.api.tool.agentic.DomainToolTracker
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor

/**
 * A tool with conditional tool unlocking that uses an LLM to orchestrate sub-tools.
 *
 * Unlike [com.embabel.agent.api.tool.agentic.simple.SimpleAgenticTool] which makes all tools
 * available immediately, a PlaybookTool allows tools to be progressively unlocked based on
 * conditions such as:
 * - Prerequisites: unlock after other tools have been called
 * - Artifacts: unlock when certain artifact types are produced
 * - Blackboard: unlock based on process state
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
 * @param llm LLM to use for orchestration. It is good practice to provide
 * @param unlockedTools Tools that are always available
 * @param lockedTools Tools with unlock conditions
 * @param systemPromptCreator Create prompt for the LLM to use, given context and input
 * @param maxIterations Maximum number of tool loop iterations
 */
data class PlaybookTool internal constructor(
    override val definition: Tool.Definition,
    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    override val llm: LlmOptions = LlmOptions(),
    internal val unlockedTools: List<Tool> = emptyList(),
    internal val lockedTools: List<LockedTool> = emptyList(),
    internal val domainToolSources: List<DomainToolSource<*>> = emptyList(),
    val systemPromptCreator: AgenticSystemPromptCreator = AgenticSystemPromptCreator { _, _ ->
        defaultSystemPrompt(definition.description)
    },
    override val maxIterations: Int = AgenticTool.DEFAULT_MAX_ITERATIONS,
) : AgenticTool<PlaybookTool> {

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
        val allStaticTools = unlockedTools + lockedTools.map { it.tool }
        if (allStaticTools.isEmpty() && domainToolSources.isEmpty()) {
            loggerFor<PlaybookTool>().warn(
                "No tools available for PlaybookTool '{}'",
                definition.name,
            )
            return Tool.Result.error("No tools available for PlaybookTool")
        }

        val (agentProcess, errorResult) = AgenticToolSupport.getAgentProcessOrError(
            definition.name,
            loggerFor<PlaybookTool>(),
        )
        if (errorResult != null) return errorResult

        val executingContext = executingOperationContextFor(agentProcess!!)
        val systemPrompt = systemPromptCreator.apply(executingContext, input)
        loggerFor<PlaybookTool>().info(
            "Executing PlaybookTool '{}' with {} unlocked tools, {} locked tools, and {} domain sources",
            definition.name,
            unlockedTools.size,
            lockedTools.size,
            domainToolSources.size,
        )

        // Create domain tool tracker if we have domain sources
        val domainToolTracker = if (domainToolSources.isNotEmpty()) {
            DomainToolTracker(domainToolSources)
        } else {
            null
        }

        // Create shared state for tracking tool calls and artifacts
        val state = PlaybookState(agentProcess, domainToolTracker)

        // Wrap unlocked tools to track state
        val wrappedUnlockedTools = unlockedTools.map { tool ->
            StateTrackingTool(tool, state)
        }

        // Wrap locked tools with conditional execution
        val wrappedLockedTools = lockedTools.map { lockedTool ->
            ConditionalTool(lockedTool.tool, lockedTool.condition, state)
        }

        // Create placeholder tools for domain tool sources
        val domainPlaceholderTools = domainToolTracker?.let { tracker ->
            domainToolSources.flatMap { source ->
                DomainToolFactory.createPlaceholderTools(source, tracker)
            }
        } ?: emptyList()

        val allWrappedTools = wrappedUnlockedTools + wrappedLockedTools + domainPlaceholderTools

        val ai = executingContext.ai()
        val output = ai
            .withLlm(llm)
            .withId("playbook-tool-${definition.name}")
            .withTools(allWrappedTools)
            .withSystemPrompt(systemPrompt)
            .generateText(input)

        return AgenticToolSupport.createResult(output, state.artifacts)
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

    override fun withLlm(llm: LlmOptions): PlaybookTool = copy(llm = llm)

    override fun withSystemPrompt(creator: AgenticSystemPromptCreator): PlaybookTool = copy(
        systemPromptCreator = creator,
    )

    override fun withMaxIterations(maxIterations: Int): PlaybookTool = copy(
        maxIterations = maxIterations,
    )

    override fun withParameter(parameter: Tool.Parameter): PlaybookTool = copy(
        definition = definition.withParameter(parameter),
    )

    override fun withToolObject(toolObject: Any): PlaybookTool {
        val additionalTools = Tool.safelyFromInstance(toolObject)
        return if (additionalTools.isEmpty()) {
            this
        } else {
            copy(unlockedTools = unlockedTools + additionalTools)
        }
    }

    /**
     * Register a domain class that can contribute @LlmTool methods when a single instance is retrieved.
     *
     * When a single artifact of the specified type is returned by any tool, any @LlmTool annotated
     * methods on that instance become available as tools.
     *
     * Example:
     * ```kotlin
     * PlaybookTool("userManager", "Manage users")
     *     .withTools(searchUserTool, getUserTool)
     *     .withDomainToolsFrom(User::class.java)  // User methods become available when a single User is retrieved
     * ```
     *
     * @param type The domain class that may contribute tools
     */
    fun <T : Any> withDomainToolsFrom(type: Class<T>): PlaybookTool = copy(
        domainToolSources = domainToolSources + DomainToolSource(type),
    )

    /**
     * Register a domain class that can contribute @LlmTool methods when a single instance is retrieved.
     * Kotlin-friendly version using reified type parameter.
     *
     * Example:
     * ```kotlin
     * PlaybookTool("userManager", "Manage users")
     *     .withTools(searchUserTool, getUserTool)
     *     .withDomainToolsFrom<User>()  // User methods become available when a single User is retrieved
     * ```
     */
    inline fun <reified T : Any> withDomainToolsFrom(): PlaybookTool =
        withDomainToolsFrom(T::class.java)

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
