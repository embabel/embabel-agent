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
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.api.tool.callback.ToolLoopInspector
import com.embabel.agent.api.tool.callback.ToolLoopTransformer
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Factory for creating [ToolLoop] instances.
 *
 * Use companion object methods to obtain instances:
 * - [default] for default configuration
 * - [withConfig] for custom configuration
 *
 * ## Threading and context propagation
 *
 * Parallel tool execution is governed by the [Asyncer] abstraction, which is the
 * single extension point for controlling threading behavior and propagating
 * execution context (e.g., security context, MDC) to tool execution threads.
 *
 * In a Spring environment, the [Asyncer] bean is injected automatically.
 * For programmatic usage, provide an [Asyncer] via [withConfig].
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

        private val DEFAULT_ASYNCER: Asyncer by lazy {
            ExecutorAsyncer(Executors.newVirtualThreadPerTaskExecutor())
        }

        /**
         * Create a factory with default configuration.
         * Uses [ToolLoopType.DEFAULT] (sequential execution) with virtual thread-based [Asyncer].
         */
        fun default(): ToolLoopFactory =
            ConfigurableToolLoopFactory(ToolLoopConfiguration(), DEFAULT_ASYNCER)

        /**
         * Create a factory with the specified configuration.
         * Uses virtual thread-based [Asyncer] for parallel execution.
         *
         * For custom threading or context propagation, use [withConfig] with an [Asyncer].
         *
         * @param config the tool loop configuration
         */
        fun withConfig(config: ToolLoopConfiguration): ToolLoopFactory =
            ConfigurableToolLoopFactory(config, DEFAULT_ASYNCER)

        /**
         * Create a factory with the specified configuration and asyncer.
         * The [Asyncer] is used for parallel tool execution, ensuring that
         * custom execution context (e.g., security context, MDC) is propagated
         * to tool execution threads.
         *
         * This is the recommended way to customize parallel execution behavior.
         *
         * @param config the tool loop configuration
         * @param asyncer asyncer for parallel mode with context propagation
         */
        fun withConfig(config: ToolLoopConfiguration, asyncer: Asyncer): ToolLoopFactory =
            ConfigurableToolLoopFactory(config, asyncer)

        /**
         * Create a factory with the specified configuration and executor.
         *
         * @param config the tool loop configuration
         * @param executor executor service for parallel mode
         * @deprecated Since 0.5.0. Use [withConfig] with an [Asyncer] instead.
         * The Asyncer abstraction provides context propagation support
         * that raw ExecutorService does not.
         */
        @Deprecated(
            message = "Use withConfig(config, asyncer) instead. The Asyncer abstraction provides context propagation.",
            replaceWith = ReplaceWith("withConfig(config, ExecutorAsyncer(executor))"),
            level = DeprecationLevel.WARNING,
        )
        fun withConfig(config: ToolLoopConfiguration, executor: ExecutorService): ToolLoopFactory =
            ConfigurableToolLoopFactory(config, ExecutorAsyncer(executor))
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
