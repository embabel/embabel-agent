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
package com.embabel.agent.api.tool.config

import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ExecutorType
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ParallelModeProperties
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [ToolLoopConfiguration].
 */
@Suppress("DEPRECATION")
class ToolLoopConfigurationTest {

    @Test
    fun `default configuration uses sequential mode`() {
        val config = ToolLoopConfiguration()

        assertEquals(ToolLoopType.DEFAULT, config.type)
        assertEquals(20, config.maxIterations)
    }

    @Test
    fun `parallel mode properties have sensible defaults`() {
        val config = ToolLoopConfiguration()

        assertEquals(Duration.ofSeconds(30), config.parallel.perToolTimeout)
        assertEquals(Duration.ofSeconds(60), config.parallel.batchTimeout)
        assertEquals(ExecutorType.VIRTUAL, config.parallel.executorType)
        assertEquals(10, config.parallel.fixedPoolSize)
    }

    @Test
    fun `can configure parallel mode`() {
        val config = ToolLoopConfiguration(
            type = ToolLoopType.PARALLEL,
            maxIterations = 10,
            parallel = ParallelModeProperties(
                perToolTimeout = Duration.ofSeconds(15),
                batchTimeout = Duration.ofSeconds(45),
                executorType = ExecutorType.FIXED,
                fixedPoolSize = 5,
            ),
        )

        assertEquals(ToolLoopType.PARALLEL, config.type)
        assertEquals(10, config.maxIterations)
        assertEquals(Duration.ofSeconds(15), config.parallel.perToolTimeout)
        assertEquals(Duration.ofSeconds(45), config.parallel.batchTimeout)
        assertEquals(ExecutorType.FIXED, config.parallel.executorType)
        assertEquals(5, config.parallel.fixedPoolSize)
    }

    @Test
    fun `executor types are available`() {
        assertEquals(3, ExecutorType.entries.size)
        assertEquals(ExecutorType.VIRTUAL, ExecutorType.valueOf("VIRTUAL"))
        assertEquals(ExecutorType.FIXED, ExecutorType.valueOf("FIXED"))
        assertEquals(ExecutorType.CACHED, ExecutorType.valueOf("CACHED"))
    }

    @Test
    fun `tool loop types are available`() {
        assertEquals(2, ToolLoopType.entries.size)
        assertEquals(ToolLoopType.DEFAULT, ToolLoopType.valueOf("DEFAULT"))
        assertEquals(ToolLoopType.PARALLEL, ToolLoopType.valueOf("PARALLEL"))
    }
}
