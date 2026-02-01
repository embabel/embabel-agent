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
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory

/**
 * Shared state for tracking playbook execution progress.
 * Used by tool wrappers to record calls and artifacts,
 * and by [ConditionalTool] to evaluate unlock conditions.
 */
@ApiStatus.Experimental
class PlaybookState {
    private val _calledToolNames = mutableSetOf<String>()
    private val _artifacts = mutableListOf<Any>()
    private var _iterationCount = 0

    val calledToolNames: Set<String> get() = _calledToolNames.toSet()
    val artifacts: List<Any> get() = _artifacts.toList()
    val iterationCount: Int get() = _iterationCount

    fun recordToolCall(toolName: String) {
        _calledToolNames.add(toolName)
        _iterationCount++
    }

    fun recordArtifact(artifact: Any) {
        _artifacts.add(artifact)
    }

    fun toContext(): PlaybookContext = PlaybookContext(
        calledToolNames = calledToolNames,
        artifacts = artifacts,
        iterationCount = iterationCount,
    )
}

/**
 * Tool wrapper that tracks calls and artifacts to shared [PlaybookState].
 */
@ApiStatus.Experimental
internal class StateTrackingTool(
    private val delegate: Tool,
    private val state: PlaybookState,
) : Tool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        val result = delegate.call(input)

        // Record that this tool was called
        state.recordToolCall(definition.name)

        // Record any artifacts
        if (result is Tool.Result.WithArtifact) {
            val artifact = result.artifact
            when (artifact) {
                is Iterable<*> -> artifact.filterNotNull().forEach { state.recordArtifact(it) }
                else -> state.recordArtifact(artifact)
            }
        }

        return result
    }

    override fun toString(): String = "StateTrackingTool(${delegate.definition.name})"
}

/**
 * Tool wrapper that checks unlock conditions before allowing execution.
 * If not unlocked, returns an informative error message guiding the LLM.
 */
@ApiStatus.Experimental
internal class ConditionalTool(
    private val delegate: Tool,
    private val condition: UnlockCondition,
    private val state: PlaybookState,
) : Tool {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = delegate.definition.name
        override val description: String = delegate.definition.description +
            "\n\nNote: This tool may require certain prerequisites before it becomes available."
        override val inputSchema: Tool.InputSchema = delegate.definition.inputSchema
    }

    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        val context = state.toContext()

        if (!condition.isSatisfied(context)) {
            logger.debug(
                "Tool '{}' is locked - condition not satisfied. Called tools: {}",
                delegate.definition.name,
                context.calledToolNames,
            )
            return Tool.Result.text(
                buildLockedMessage(context)
            )
        }

        logger.info("Tool '{}' is unlocked, executing", delegate.definition.name)

        val result = delegate.call(input)

        // Record that this tool was called
        state.recordToolCall(delegate.definition.name)

        // Record any artifacts
        if (result is Tool.Result.WithArtifact) {
            val artifact = result.artifact
            when (artifact) {
                is Iterable<*> -> artifact.filterNotNull().forEach { state.recordArtifact(it) }
                else -> state.recordArtifact(artifact)
            }
        }

        return result
    }

    private fun buildLockedMessage(context: PlaybookContext): String {
        val hint = when (condition) {
            is UnlockCondition.AfterTools -> {
                val missing = condition.prerequisites.filter { it !in context.calledToolNames }
                "You must first use: ${missing.joinToString(", ")}"
            }
            is UnlockCondition.OnArtifact -> {
                "You must first produce an artifact of type: ${condition.artifactType.simpleName}"
            }
            is UnlockCondition.AllOf -> {
                "Multiple conditions must be met before this tool is available."
            }
            is UnlockCondition.AnyOf -> {
                "At least one prerequisite condition must be met."
            }
            is UnlockCondition.WhenPredicate -> {
                "Prerequisites have not been met yet."
            }
        }
        return "This tool is not yet available. $hint"
    }

    override fun toString(): String = "ConditionalTool(${delegate.definition.name})"
}
