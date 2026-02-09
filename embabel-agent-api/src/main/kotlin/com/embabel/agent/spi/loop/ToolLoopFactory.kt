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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ExecutorType
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.agent.spi.loop.support.ParallelToolLoop
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Factory for creating [ToolLoop] instances.
 *
 * Use companion object methods to obtain instances:
 * - [default] for default configuration
 * - [withConfig] for custom configuration
 */
interface ToolLoopFactory {

    /**
     * Create a [ToolLoop] instance.
     *
     * @param llmMessageSender message sender for LLM communication
     * @param objectMapper for JSON deserialization
     * @param injectionStrategy strategy for dynamic tool injection
     * @param maxIterations maximum loop iterations
     * @param toolDecorator optional decorator for injected tools
     */
    fun create(
        llmMessageSender: LlmMessageSender,
        objectMapper: ObjectMapper,
        injectionStrategy: ToolInjectionStrategy,
        maxIterations: Int,
        toolDecorator: ((Tool) -> Tool)?,
    ): ToolLoop

    companion object {
        /**
         * Create a factory with default configuration.
         * Uses [ToolLoopType.DEFAULT] (sequential execution).
         */
        fun default(): ToolLoopFactory =
            ConfigurableToolLoopFactory(ToolLoopConfiguration())

        /**
         * Create a factory with the specified configuration.
         *
         * @param config the tool loop configuration
         */
        fun withConfig(config: ToolLoopConfiguration): ToolLoopFactory =
            ConfigurableToolLoopFactory(config)

        /**
         * Create a factory with the specified configuration and executor.
         *
         * @param config the tool loop configuration
         * @param executor executor service for parallel mode
         */
        fun withConfig(config: ToolLoopConfiguration, executor: ExecutorService): ToolLoopFactory =
            ConfigurableToolLoopFactory(config, executor)
    }
}

/**
 * Internal [ToolLoopFactory] implementation based on [ToolLoopConfiguration].
 */
internal class ConfigurableToolLoopFactory(
    private val config: ToolLoopConfiguration,
    private val executor: ExecutorService? = null,
) : ToolLoopFactory {

    override fun create(
        llmMessageSender: LlmMessageSender,
        objectMapper: ObjectMapper,
        injectionStrategy: ToolInjectionStrategy,
        maxIterations: Int,
        toolDecorator: ((Tool) -> Tool)?,
    ): ToolLoop = when (config.type) {
        ToolLoopType.DEFAULT -> DefaultToolLoop(
            llmMessageSender = llmMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = maxIterations,
            toolDecorator = toolDecorator,
        )
        ToolLoopType.PARALLEL -> createParallelToolLoop(
            llmMessageSender = llmMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = maxIterations,
            toolDecorator = toolDecorator,
        )
    }

    private fun createParallelToolLoop(
        llmMessageSender: LlmMessageSender,
        objectMapper: ObjectMapper,
        injectionStrategy: ToolInjectionStrategy,
        maxIterations: Int,
        toolDecorator: ((Tool) -> Tool)?,
    ): ToolLoop = ParallelToolLoop(
        llmMessageSender = llmMessageSender,
        objectMapper = objectMapper,
        injectionStrategy = injectionStrategy,
        maxIterations = maxIterations,
        toolDecorator = toolDecorator,
        executor = executor ?: createExecutor(),
        parallelConfig = config.parallel,
    )

    private fun createExecutor(): ExecutorService = when (config.parallel.executorType) {
        ExecutorType.VIRTUAL -> Executors.newVirtualThreadPerTaskExecutor()
        ExecutorType.FIXED -> Executors.newFixedThreadPool(config.parallel.fixedPoolSize)
        ExecutorType.CACHED -> Executors.newCachedThreadPool()
    }
}
