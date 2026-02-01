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
package com.embabel.agent.api.tool.agentic

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.config.spring.executingOperationContextFor
import com.embabel.common.ai.model.LlmOptions
import org.slf4j.Logger

/**
 * Configuration for agentic tool execution.
 */
data class AgenticExecutionConfig(
    val toolName: String,
    val llm: LlmOptions,
    val systemPrompt: String,
    val tools: List<Tool>,
    val maxIterations: Int = AgenticTool.DEFAULT_MAX_ITERATIONS,
)

/**
 * Result of agentic tool execution.
 */
data class AgenticExecutionResult(
    val output: String,
    val artifacts: List<Any>,
)

/**
 * Shared support utilities for agentic tool implementations.
 */
object AgenticToolSupport {

    /**
     * Get the current AgentProcess or return an error result.
     */
    fun getAgentProcessOrError(toolName: String, logger: Logger): Pair<AgentProcess?, Tool.Result?> {
        val agentProcess = AgentProcess.get()
        if (agentProcess == null) {
            logger.error("No AgentProcess context available for '{}'", toolName)
            return null to Tool.Result.error("No AgentProcess context available")
        }
        return agentProcess to null
    }

    /**
     * Execute the agentic tool loop with the given configuration.
     */
    fun execute(
        config: AgenticExecutionConfig,
        agentProcess: AgentProcess,
    ): AgenticExecutionResult {
        val ai = executingOperationContextFor(agentProcess).ai()
        val output = ai
            .withLlm(config.llm)
            .withId("agentic-tool-${config.toolName}")
            .withTools(config.tools)
            .withSystemPrompt(config.systemPrompt)
            .generateText("")

        // Artifacts are collected by the tool wrappers, not here
        return AgenticExecutionResult(output, emptyList())
    }

    /**
     * Convert artifacts to a Tool.Result.
     */
    fun createResult(output: String, artifacts: List<Any>): Tool.Result {
        return when (artifacts.size) {
            0 -> Tool.Result.text(output)
            1 -> Tool.Result.withArtifact(output, artifacts.single())
            else -> Tool.Result.withArtifact(output, artifacts.toList())
        }
    }
}

/**
 * Mutable artifact collector for use during agentic execution.
 */
class ArtifactCollector {
    private val _artifacts = mutableListOf<Any>()

    val artifacts: List<Any> get() = _artifacts.toList()

    fun add(artifact: Any) {
        _artifacts.add(artifact)
    }

    fun addAll(artifacts: Iterable<Any>) {
        _artifacts.addAll(artifacts)
    }
}
