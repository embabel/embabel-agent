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

import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ParallelModeProperties
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.agent.spi.loop.support.ParallelToolLoop
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Unit tests for [ToolLoopFactory].
 */
class ToolLoopFactoryTest {

    private val mockMessageSender = mockk<LlmMessageSender>()
    private val objectMapper = ObjectMapper()
    private val injectionStrategy = ToolInjectionStrategy.NONE

    @Test
    fun `default factory creates DefaultToolLoop`() {
        val factory = ToolLoopFactory.default()

        val toolLoop = factory.create(
            llmMessageSender = mockMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = 20,
            toolDecorator = null,
        )

        assertNotNull(toolLoop)
        assertTrue(toolLoop is DefaultToolLoop)
    }

    @Test
    fun `withConfig creates factory with custom configuration`() {
        val config = ToolLoopConfiguration(
            type = ToolLoopType.DEFAULT,
            maxIterations = 10,
        )
        val factory = ToolLoopFactory.withConfig(config)

        val toolLoop = factory.create(
            llmMessageSender = mockMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = 15,
            toolDecorator = null,
        )

        assertNotNull(toolLoop)
        assertTrue(toolLoop is DefaultToolLoop)
    }

    @Test
    fun `parallel type creates ParallelToolLoop`() {
        val config = ToolLoopConfiguration(type = ToolLoopType.PARALLEL)
        val factory = ToolLoopFactory.withConfig(config)

        val toolLoop = factory.create(
            llmMessageSender = mockMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = 20,
            toolDecorator = null,
        )

        assertNotNull(toolLoop)
        assertTrue(toolLoop is ParallelToolLoop)

        // Cleanup
        (factory as Closeable).close()
    }

    @Nested
    inner class ExecutorShutdownTest {

        @Test
        fun `close shuts down created executor when parallel mode used`() {
            val config = ToolLoopConfiguration(type = ToolLoopType.PARALLEL)
            val factory = ToolLoopFactory.withConfig(config)

            // Create parallel tool loop to initialize executor
            factory.create(
                llmMessageSender = mockMessageSender,
                objectMapper = objectMapper,
                injectionStrategy = injectionStrategy,
                maxIterations = 20,
                toolDecorator = null,
            )

            // Get executor via reflection before close
            val lazyDelegateField = factory::class.java.getDeclaredField("lazyExecutorDelegate")
            lazyDelegateField.isAccessible = true
            val lazyDelegate = lazyDelegateField.get(factory) as Lazy<*>
            val executor = lazyDelegate.value as ExecutorService

            assertFalse(executor.isShutdown, "Executor should not be shut down before close")

            // Close should shut down the executor
            (factory as Closeable).close()

            assertTrue(executor.isShutdown, "Executor should be shut down after close")
        }

        @Test
        fun `close does not shut down external executor`() {
            val externalExecutor = Executors.newSingleThreadExecutor()
            try {
                val config = ToolLoopConfiguration(type = ToolLoopType.PARALLEL)
                val factory = ToolLoopFactory.withConfig(config, externalExecutor)

                factory.create(
                    llmMessageSender = mockMessageSender,
                    objectMapper = objectMapper,
                    injectionStrategy = injectionStrategy,
                    maxIterations = 20,
                    toolDecorator = null,
                )

                // Close factory
                (factory as Closeable).close()

                // External executor should still be usable
                assertFalse(externalExecutor.isShutdown, "External executor should not be shut down")
            } finally {
                externalExecutor.shutdown()
            }
        }

        @Test
        fun `close is no-op when parallel mode never used`() {
            val config = ToolLoopConfiguration(type = ToolLoopType.DEFAULT)
            val factory = ToolLoopFactory.withConfig(config)

            // Create default tool loop (no executor created)
            factory.create(
                llmMessageSender = mockMessageSender,
                objectMapper = objectMapper,
                injectionStrategy = injectionStrategy,
                maxIterations = 20,
                toolDecorator = null,
            )

            // Close should be no-op (no exception, no executor to check)
            (factory as Closeable).close()
        }

        @Test
        fun `factory implements Closeable`() {
            val factory = ToolLoopFactory.default()
            assertTrue(factory is Closeable)
        }
    }
}
