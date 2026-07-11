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
package com.embabel.agent.mcpserver.health

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.modelcontextprotocol.server.McpSyncServer
import com.embabel.agent.mcpserver.support.toolNames
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.ConfigurableEnvironment

class McpServerHealthEvaluatorTest {

    private lateinit var tracker: McpServerInitializationTracker
    private lateinit var properties: McpServerHealthProperties
    private lateinit var applicationContext: ConfigurableApplicationContext
    private lateinit var environment: ConfigurableEnvironment
    private lateinit var syncServer: McpSyncServer
    private lateinit var evaluator: McpServerHealthEvaluator

    @BeforeEach
    fun setUp() {
        mockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
        tracker = McpServerInitializationTracker()
        properties = McpServerHealthProperties()
        applicationContext = mockk(relaxed = true)
        environment = mockk(relaxed = true)
        syncServer = mockk(relaxed = true)

        every { applicationContext.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "SYNC"
        every { applicationContext.getBeanProvider(McpSyncServer::class.java).ifAvailable } returns syncServer
        every { syncServer.toolNames() } returns listOf("helloBanner", "otherTool")

        evaluator = McpServerHealthEvaluator(tracker, properties, applicationContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("com.embabel.agent.mcpserver.support.ExtensionsKt")
    }

    @Test
    fun `not started is unhealthy`() {
        val status = evaluator.evaluate()

        assertFalse(status.isHealthy)
        assertEquals(McpServerInitializationState.NOT_STARTED, tracker.state)
        assertTrue(status.issues.any { it.contains("has not started") })
        assertEquals(2, status.toolCount)
    }

    @Test
    fun `initializing is unhealthy`() {
        tracker.markInitializing()

        val status = evaluator.evaluate()

        assertFalse(status.isHealthy)
        assertTrue(status.issues.any { it.contains("still initializing") })
    }

    @Test
    fun `failed is unhealthy and keeps tracker issues`() {
        tracker.markInitializing()
        tracker.markFailed("boom")

        val status = evaluator.evaluate()

        assertFalse(status.isHealthy)
        assertTrue(status.issues.contains("boom"))
    }

    @Test
    fun `ready with enough tools is healthy`() {
        tracker.markInitializing()
        tracker.markReady()
        properties.minTools = 1

        val status = evaluator.evaluate()

        assertTrue(status.isHealthy)
        assertTrue(status.issues.isEmpty())
        assertEquals(2, status.toolCount)
    }

    @Test
    fun `ready below min tools is unhealthy`() {
        tracker.markInitializing()
        tracker.markReady()
        properties.minTools = 5

        val status = evaluator.evaluate()

        assertFalse(status.isHealthy)
        assertTrue(status.issues.any { it.contains("below minimum") })
    }
}
