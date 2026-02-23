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
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.agent.spi.loop.support.ParallelToolLoop
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * Unit tests for [ToolLoopFactory].
 */
class ToolLoopFactoryTest {

    private val mockMessageSender = mockk<LlmMessageSender>()
    private val objectMapper = ObjectMapper()
    private val injectionStrategy = ToolInjectionStrategy.NONE
    private val asyncer = ExecutorAsyncer(Executors.newFixedThreadPool(4))

    @Test
    fun `creates DefaultToolLoop for default type`() {
        val config = ToolLoopConfiguration()
        val factory = ToolLoopFactory.create(config, asyncer)

        val toolLoop = factory.create(
            llmMessageSender = mockMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = 20,
            toolDecorator = null,
            inspectors = emptyList(),
            transformers = emptyList(),
        )

        assertNotNull(toolLoop)
        assertTrue(toolLoop is DefaultToolLoop)
    }

    @Test
    fun `creates ParallelToolLoop for parallel type`() {
        val config = ToolLoopConfiguration(type = ToolLoopType.PARALLEL)
        val factory = ToolLoopFactory.create(config, asyncer)

        val toolLoop = factory.create(
            llmMessageSender = mockMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = 20,
            toolDecorator = null,
            inspectors = emptyList(),
            transformers = emptyList(),
        )

        assertNotNull(toolLoop)
        assertTrue(toolLoop is ParallelToolLoop)
    }

    @Test
    fun `custom configuration is respected`() {
        val config = ToolLoopConfiguration(
            type = ToolLoopType.DEFAULT,
            maxIterations = 10,
        )
        val factory = ToolLoopFactory.create(config, asyncer)

        val toolLoop = factory.create(
            llmMessageSender = mockMessageSender,
            objectMapper = objectMapper,
            injectionStrategy = injectionStrategy,
            maxIterations = 15,
            toolDecorator = null,
            inspectors = emptyList(),
            transformers = emptyList(),
        )

        assertNotNull(toolLoop)
        assertTrue(toolLoop is DefaultToolLoop)
    }
}
