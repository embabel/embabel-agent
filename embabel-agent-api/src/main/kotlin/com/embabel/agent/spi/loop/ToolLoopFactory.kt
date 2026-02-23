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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.agent.spi.loop.support.ParallelToolLoop
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.api.tool.callback.ToolLoopTransformer
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Factory for creating [ToolLoop] instances.
 *
 * ## Threading and context propagation
 *
 * Parallel tool execution is governed by the [Asyncer] abstraction, which is the
 * single extension point for controlling threading behavior and propagating
 * execution context (e.g., security context, MDC) to tool execution threads.
 *
 * An [Asyncer] is always required. In a Spring environment, the [Asyncer] bean
 * is injected automatically via [com.embabel.agent.spi.config.spring.AsyncConfiguration].
 * For programmatic usage, provide an [Asyncer] via [create].
 *
 * @see Asyncer
 */
fun interface ToolLoopFactory {

    /**
     * Create a [ToolLoop] instance.
     *
     * @param llmMessageSender message sender for LLM communication
     * @param objectMapper for JSON deserialization
     * @param injectionStrategy strategy for dynamic tool injection
     * @param maxIterations maximum loop iterations
     * @param toolDecorator optional decorator for injected tools
     * @param inspectors read-only observers for tool loop lifecycle events
     * @param transformers transformers for modifying conversation history or tool results
     */
    fun create(
        llmMessageSender: LlmMessageSender,
        objectMapper: ObjectMapper,
        injectionStrategy: ToolInjectionStrategy,
        maxIterations: Int,
        toolDecorator: ((Tool) -> Tool)?,
        inspectors: List<ToolLoopInspector>,
        transformers: List<ToolLoopTransformer>,
    ): ToolLoop

    companion object {
        /**
         * Create a factory with the specified configuration and asyncer.
         *
         * @param config the tool loop configuration
         * @param asyncer asyncer for parallel mode with context propagation
         */
        fun create(config: ToolLoopConfiguration, asyncer: Asyncer): ToolLoopFactory =
            ConfigurableToolLoopFactory(config, asyncer)
    }
}

/**
 * Internal [ToolLoopFactory] implementation based on [ToolLoopConfiguration].
 *
 * @param config the tool loop configuration
 * @param asyncer the asyncer to use for parallel tool execution
 */
internal class ConfigurableToolLoopFactory(
    private val config: ToolLoopConfiguration,
    private val asyncer: Asyncer,
) : ToolLoopFactory {

    override fun create(
        llmMessageSender: LlmMessageSender,
        objectMapper: ObjectMapper,
        injectionStrategy: ToolInjectionStrategy,
        maxIterations: Int,
        toolDecorator: ((Tool) -> Tool)?,
        inspectors: List<ToolLoopInspector>,
        transformers: List<ToolLoopTransformer>,
    ): ToolLoop = when (config.type) {
        ToolLoopType.DEFAULT -> DefaultToolLoop(
            llmMessageSender = llmMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = maxIterations,
            toolDecorator = toolDecorator,
            inspectors = inspectors,
            transformers = transformers,
        )
        ToolLoopType.PARALLEL -> ParallelToolLoop(
            llmMessageSender = llmMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = maxIterations,
            toolDecorator = toolDecorator,
            inspectors = inspectors,
            transformers = transformers,
            asyncer = asyncer,
            parallelConfig = config.parallel,
        )
    }
}
