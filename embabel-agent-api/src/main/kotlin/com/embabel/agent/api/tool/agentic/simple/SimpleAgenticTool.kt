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

import com.embabel.agent.api.common.ExecutingOperationContext
import com.embabel.agent.api.tool.ArtifactSinkingTool
import com.embabel.agent.api.tool.ListSink
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.agentic.AgenticSystemPromptCreator
import com.embabel.agent.api.tool.agentic.AgenticTool
import com.embabel.agent.api.tool.agentic.AgenticToolSupport
import com.embabel.agent.api.tool.agentic.DomainToolFactory
import com.embabel.agent.api.tool.agentic.DomainToolPredicate
import com.embabel.agent.api.tool.agentic.DomainToolSource
import com.embabel.agent.api.tool.agentic.DomainToolTracker
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.loggerFor

/**
 * Artifact sink that also notifies a domain tool tracker when artifacts are captured.
 */
internal class DomainAwareSink(
    private val artifacts: MutableList<Any>,
    private val domainToolTracker: DomainToolTracker?,
) : (Any) -> Unit {
    override fun invoke(artifact: Any) {
        artifacts.add(artifact)
        domainToolTracker?.tryBindArtifact(artifact)
    }
}

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
 * @param systemPromptCreator Create prompt for the LLM to use, given context and input
 * @param maxIterations Maximum number of tool loop iterations
 * @param captureNestedArtifacts Whether to capture artifacts from nested AgenticTools
 */
data class SimpleAgenticTool(
    override val definition: Tool.Definition,
    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    override val llm: LlmOptions = LlmOptions(),
    val tools: List<Tool> = emptyList(),
    internal val domainToolSources: List<DomainToolSource<*>> = emptyList(),
    internal val autoDiscovery: Boolean = false,
    val systemPromptCreator: AgenticSystemPromptCreator = AgenticSystemPromptCreator { _, _ ->
        AgenticTool.defaultSystemPrompt(definition.description)
    },
    override val maxIterations: Int = AgenticTool.DEFAULT_MAX_ITERATIONS,
    val captureNestedArtifacts: Boolean = false,
) : AgenticTool<SimpleAgenticTool> {

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
        if (tools.isEmpty() && domainToolSources.isEmpty() && !autoDiscovery) {
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

        val executingContext = executingOperationContextFor(agentProcess!!)
        val systemPrompt = systemPromptCreator.apply(executingContext, input)
        loggerFor<SimpleAgenticTool>().info(
            "Executing SimpleAgenticTool '{}' with {} tools, {} domain sources, autoDiscovery={}",
            definition.name,
            tools.size,
            domainToolSources.size,
            autoDiscovery,
        )

        // Create domain tool tracker if we have domain sources or auto-discovery is enabled
        val domainToolTracker = if (domainToolSources.isNotEmpty() || autoDiscovery) {
            DomainToolTracker(
                sources = domainToolSources,
                autoDiscovery = autoDiscovery,
                agentProcess = agentProcess,
            )
        } else {
            null
        }

        // Wrap tools to capture any artifacts they produce
        val artifacts = mutableListOf<Any>()
        val sink = DomainAwareSink(artifacts, domainToolTracker)
        val wrappedTools = tools.map { tool ->
            // Skip wrapping nested AgenticTools if captureNestedArtifacts is false
            if (!captureNestedArtifacts && tool is AgenticTool<*>) {
                tool
            } else {
                ArtifactSinkingTool(tool, Any::class.java, sink)
            }
        }

        // Create placeholder tools for domain tool sources
        val domainPlaceholderTools = domainToolTracker?.let { tracker ->
            domainToolSources.flatMap { source ->
                DomainToolFactory.createPlaceholderTools(source, tracker)
            }
        } ?: emptyList()

        val allTools = wrappedTools + domainPlaceholderTools

        val ai = executingContext.ai()
        val output = ai
            .withLlm(llm)
            .withId("simple-agentic-tool-${definition.name}")
            .withTools(allTools)
            .withSystemPrompt(systemPrompt)
            .generateText(input)

        return AgenticToolSupport.createResult(output, artifacts)
    }

    override fun withLlm(llm: LlmOptions): SimpleAgenticTool = copy(llm = llm)

    override fun withSystemPrompt(creator: AgenticSystemPromptCreator): SimpleAgenticTool = copy(
        systemPromptCreator = creator,
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

    override fun <T : Any> withDomainToolsFrom(
        type: Class<T>,
        predicate: DomainToolPredicate<T>,
    ): SimpleAgenticTool = copy(
        domainToolSources = domainToolSources + DomainToolSource(type, predicate),
    )

    /**
     * Register a domain class with a predicate.
     * Kotlin-friendly version using reified type parameter.
     */
    inline fun <reified T : Any> withDomainToolsFrom(
        noinline predicate: (T, com.embabel.agent.core.AgentProcess?) -> Boolean,
    ): SimpleAgenticTool = withDomainToolsFrom(T::class.java, DomainToolPredicate(predicate))

    /**
     * Register a domain class that can contribute @LlmTool methods when a single instance is retrieved.
     * Kotlin-friendly version using reified type parameter.
     */
    inline fun <reified T : Any> withDomainToolsFrom(): SimpleAgenticTool =
        withDomainToolsFrom(T::class.java)

    override fun withAnyDomainTools(): SimpleAgenticTool = copy(autoDiscovery = true)
}
