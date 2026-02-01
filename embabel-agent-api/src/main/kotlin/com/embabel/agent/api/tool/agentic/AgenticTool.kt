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
import com.embabel.common.ai.model.LlmOptions

/**
 * Create a system prompt given the current [AgentProcess] as context.
 */
typealias AgenticSystemPromptCreator = (AgentProcess) -> String

/**
 * A tool that uses an LLM to orchestrate sub-tools.
 *
 * Unlike a regular [Tool] which executes deterministic logic, an [AgenticTool]
 * uses an LLM to decide which sub-tools to call based on a prompt.
 *
 * Implementations differ in how they manage tool availability:
 * - [com.embabel.agent.api.tool.agentic.simple.SimpleAgenticTool]: All tools available immediately
 * - [com.embabel.agent.api.tool.agentic.playbook.PlaybookTool]: Progressive unlock via conditions
 * - [com.embabel.agent.api.tool.agentic.state.StateMachineTool]: State-based availability
 *
 * All implementations share a consistent fluent API for configuration.
 */
interface AgenticTool : Tool {

    /**
     * LLM options for orchestration.
     */
    val llm: LlmOptions

    /**
     * Maximum number of tool loop iterations.
     */
    val maxIterations: Int

    /**
     * Create a copy with different LLM options.
     */
    fun withLlm(llm: LlmOptions): AgenticTool

    /**
     * Create a copy with a fixed system prompt.
     */
    fun withSystemPrompt(prompt: String): AgenticTool

    /**
     * Create a copy with a different max iterations limit.
     */
    fun withMaxIterations(maxIterations: Int): AgenticTool

    /**
     * Create a copy with an additional parameter in the definition.
     */
    fun withParameter(parameter: Tool.Parameter): AgenticTool

    /**
     * Create a copy with tools extracted from an object with @LlmTool methods.
     * If the object has no @LlmTool methods, returns this unchanged.
     */
    fun withToolObject(toolObject: Any): AgenticTool

    companion object {
        /**
         * Default max iterations for agentic tools.
         */
        const val DEFAULT_MAX_ITERATIONS = 20

        /**
         * Default system prompt template.
         */
        fun defaultSystemPrompt(description: String) = """
            You are an intelligent agent that can use tools to help you complete tasks.
            Use the provided tools to perform the following task:
            $description
            """.trimIndent()
    }
}
