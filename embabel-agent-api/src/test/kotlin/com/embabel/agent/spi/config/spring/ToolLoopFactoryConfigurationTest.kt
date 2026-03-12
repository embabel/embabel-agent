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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType
import com.embabel.agent.spi.support.ExecutorAsyncer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * Unit tests for [ToolLoopFactoryConfiguration].
 */
class ToolLoopFactoryConfigurationTest {

    private val asyncer = ExecutorAsyncer(Executors.newCachedThreadPool())

    @Test
    fun `creates ToolLoopFactory bean with default config`() {
        val config = ToolLoopConfiguration()
        val configuration = ToolLoopFactoryConfiguration(config)

        val factory = configuration.toolLoopFactory(asyncer)

        assertNotNull(factory)
    }

    @Test
    fun `creates ToolLoopFactory bean with parallel config`() {
        val config = ToolLoopConfiguration(type = ToolLoopType.PARALLEL)
        val configuration = ToolLoopFactoryConfiguration(config)

        val factory = configuration.toolLoopFactory(asyncer)

        assertNotNull(factory)
    }
}
