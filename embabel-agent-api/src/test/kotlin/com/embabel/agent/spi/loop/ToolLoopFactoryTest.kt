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
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
    }
}
