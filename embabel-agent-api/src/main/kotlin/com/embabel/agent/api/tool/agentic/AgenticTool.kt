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

import com.embabel.agent.api.common.ExecutingOperationContext
import com.embabel.agent.api.tool.Tool
import com.embabel.common.ai.model.LlmOptions
import java.util.function.BiFunction

/**
 * Creates a system prompt given the execution context and input.
 *
 * This is a functional interface extending [BiFunction] for Java interoperability.
 * The context provides access to the blackboard, process options, and other
 * execution state. The input is the string passed to the agentic tool.
 *
 * Example usage in Kotlin:
 * ```kotlin
 * tool.withSystemPrompt { ctx, input ->
 *     "You are helping user ${ctx.processContext.processOptions.contextId}. Task: $input"
 * }
 * ```
 *
 * Example usage in Java:
 * ```java
 * tool.withSystemPrompt((ctx, input) ->
 *     "You are helping user " + ctx.getProcessContext().getProcessOptions().getContextId()
 * );
 * ```
 */
@FunctionalInterface
fun interface AgenticSystemPromptCreator : BiFunction<ExecutingOperationContext, String, String> {
    override fun apply(context: ExecutingOperationContext, input: String): String
}

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
 * The type parameter THIS enables fluent method chaining that preserves the concrete type.
 *
 * @param THIS The concrete implementation type for fluent method chaining
 */
interface AgenticTool<THIS : AgenticTool<THIS>> : Tool {

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
    fun withLlm(llm: LlmOptions): THIS

    /**
     * Create a copy with a dynamic system prompt creator.
     * The creator receives the execution context and input string.
     */
    fun withSystemPrompt(creator: AgenticSystemPromptCreator): THIS

    /**
     * Create a copy with a fixed system prompt.
     * This is a convenience method that delegates to [withSystemPrompt] with a creator.
     */
    fun withSystemPrompt(prompt: String): THIS =
        withSystemPrompt { _, _ -> prompt }

    /**
     * Create a copy with a different max iterations limit.
     */
    fun withMaxIterations(maxIterations: Int): THIS

    /**
     * Create a copy with an additional parameter in the definition.
     */
    fun withParameter(parameter: Tool.Parameter): THIS

    /**
     * Create a copy with tools extracted from an object with @LlmTool methods.
     * If the object has no @LlmTool methods, returns this unchanged.
     */
    fun withToolObject(toolObject: Any): THIS

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
