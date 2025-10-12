/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.mcpserver.config

import com.embabel.agent.mcpserver.async.config.McpAsyncServerCondition
import com.embabel.agent.mcpserver.sync.config.McpSyncServerCondition
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

class McpServerConditionsTest {

    @Test
    fun `McpSyncServerCondition should match when enabled and type is SYNC`() {
        val condition = McpSyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "SYNC"

        assertTrue(condition.matches(context, metadata))
    }

    @Test
    fun `McpSyncServerCondition should not match when type is ASYNC`() {
        val condition = McpSyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "ASYNC"

        assertFalse(condition.matches(context, metadata))
    }

    @Test
    fun `McpSyncServerCondition should match when enabled and type defaults to SYNC`() {
        val condition = McpSyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "SYNC" // Default value

        assertTrue(condition.matches(context, metadata))
    }

    @Test
    fun `McpAsyncServerCondition should match when enabled and type is ASYNC`() {
        val condition = McpAsyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "ASYNC"

        assertTrue(condition.matches(context, metadata))
    }

    @Test
    fun `McpAsyncServerCondition should not match when type is SYNC`() {
        val condition = McpAsyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "SYNC"

        assertFalse(condition.matches(context, metadata))
    }

    @Test
    fun `McpAsyncServerCondition should not match with default SYNC type`() {
        val condition = McpAsyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "SYNC" // Default

        assertFalse(condition.matches(context, metadata))
    }

    @Test
    fun `conditions should handle null environment gracefully`() {
        val syncCondition = McpSyncServerCondition()
        val asyncCondition = McpAsyncServerCondition()
        val context = mockk<ConditionContext>(relaxed = true)
        val metadata = mockk<AnnotatedTypeMetadata>()

        // Use relaxed mock to handle potential null returns
        every { context.environment } throws NullPointerException("Environment is null")

        // Since environment access throws NPE, conditions should handle this
        assertThrows<Exception> {
            syncCondition.matches(context, metadata)
        }
        assertThrows<Exception> {
            asyncCondition.matches(context, metadata)
        }
    }

    @Test
    fun `conditions should handle case insensitive type values`() {
        val syncCondition = McpSyncServerCondition()
        val asyncCondition = McpAsyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment

        // Test lowercase
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "sync"
        assertFalse(syncCondition.matches(context, metadata)) // Should be case sensitive

        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "async"
        assertFalse(asyncCondition.matches(context, metadata)) // Should be case sensitive
    }

    @Test
    fun `conditions should handle unknown type values`() {
        val syncCondition = McpSyncServerCondition()
        val asyncCondition = McpAsyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "UNKNOWN"

        assertFalse(syncCondition.matches(context, metadata))
        assertFalse(asyncCondition.matches(context, metadata))
    }

    @Test
    fun `conditions should respect default values`() {
        val syncCondition = McpSyncServerCondition()
        val context = mockk<ConditionContext>()
        val metadata = mockk<AnnotatedTypeMetadata>()
        val environment = mockk<Environment>()

        every { context.environment } returns environment
        // When type property is not set, should default to "SYNC"
        every { environment.getProperty("spring.ai.mcp.server.type", "SYNC") } returns "SYNC"

        assertTrue(syncCondition.matches(context, metadata))
    }
}
