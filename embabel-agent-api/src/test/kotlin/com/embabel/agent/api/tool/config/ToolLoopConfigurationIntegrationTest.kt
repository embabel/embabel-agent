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
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Duration

/**
 * Spring Boot integration test for [ToolLoopConfiguration] properties binding.
 */
@SpringBootTest(classes = [ToolLoopConfigurationIntegrationTest.TestConfig::class])
@TestPropertySource(
    properties = [
        "embabel.agent.platform.toolloop.type=parallel",
        "embabel.agent.platform.toolloop.max-iterations=15",
        "embabel.agent.platform.toolloop.parallel.per-tool-timeout=20s",
        "embabel.agent.platform.toolloop.parallel.batch-timeout=45s",
        "embabel.agent.platform.toolloop.parallel.executor-type=fixed",
        "embabel.agent.platform.toolloop.parallel.fixed-pool-size=8",
    ]
)
class ToolLoopConfigurationIntegrationTest {

    @EnableConfigurationProperties(ToolLoopConfiguration::class)
    class TestConfig

    @Autowired
    lateinit var config: ToolLoopConfiguration

    @Test
    fun `properties are bound correctly`() {
        assertEquals(ToolLoopType.PARALLEL, config.type)
        assertEquals(15, config.maxIterations)
    }

    @Test
    fun `parallel mode properties are bound correctly`() {
        assertEquals(Duration.ofSeconds(20), config.parallel.perToolTimeout)
        assertEquals(Duration.ofSeconds(45), config.parallel.batchTimeout)
        assertEquals(ExecutorType.FIXED, config.parallel.executorType)
        assertEquals(8, config.parallel.fixedPoolSize)
    }
}
