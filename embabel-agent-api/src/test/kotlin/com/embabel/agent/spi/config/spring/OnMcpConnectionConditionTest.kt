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
package com.embabel.agent.spi.config.spring

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("OnMcpConnectionCondition")
class OnMcpConnectionConditionTest {

    private lateinit var condition: OnMcpConnectionCondition
    private lateinit var context: ConditionContext
    private lateinit var metadata: AnnotatedTypeMetadata
    private lateinit var environment: Environment

    @BeforeEach
    fun setUp() {
        condition = OnMcpConnectionCondition()
        environment = mockk(relaxed = true)
        context = mockk()
        every { context.environment } returns environment
        metadata = mockk()
    }

    private fun mockAnnotationAttributes(vararg connectionNames: String) {
        val attributes = mapOf<String, Any>(
            "value" to connectionNames
        )
        every {
            metadata.getAnnotationAttributes(ConditionalOnMcpConnection::class.java.name)
        } returns attributes
    }

    private fun mockStdioConnectionExists(connectionName: String) {
        every {
            environment.containsProperty("spring.ai.mcp.client.stdio.connections.$connectionName.command")
        } returns true
    }

    private fun mockSseConnectionExists(connectionName: String) {
        every {
            environment.containsProperty("spring.ai.mcp.client.sse.connections.$connectionName.url")
        } returns true
    }

    @Nested
    @DisplayName("when annotation attributes are missing")
    inner class MissingAttributes {

        @Test
        fun `returns false when no annotation attributes present`() {
            every {
                metadata.getAnnotationAttributes(ConditionalOnMcpConnection::class.java.name)
            } returns null

            val result = condition.matches(context, metadata)

            assertFalse(result)
        }

        @Test
        fun `returns false when value attribute is missing`() {
            every {
                metadata.getAnnotationAttributes(ConditionalOnMcpConnection::class.java.name)
            } returns emptyMap()

            val result = condition.matches(context, metadata)

            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("with stdio connections")
    inner class StdioConnections {

        @Test
        fun `matches when stdio connection exists`() {
            mockAnnotationAttributes("github-mcp")
            mockStdioConnectionExists("github-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `does not match when stdio connection does not exist`() {
            mockAnnotationAttributes("github-mcp")

            val result = condition.matches(context, metadata)

            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("with sse connections")
    inner class SseConnections {

        @Test
        fun `matches when sse connection exists`() {
            mockAnnotationAttributes("github-mcp")
            mockSseConnectionExists("github-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `does not match when sse connection does not exist`() {
            mockAnnotationAttributes("github-mcp")

            val result = condition.matches(context, metadata)

            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("with multiple connections (ANY match)")
    inner class MultipleConnections {

        @Test
        fun `matches when first connection exists`() {
            mockAnnotationAttributes("brave-search-mcp", "fetch-mcp", "wikipedia-mcp")
            mockStdioConnectionExists("brave-search-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `matches when middle connection exists`() {
            mockAnnotationAttributes("brave-search-mcp", "fetch-mcp", "wikipedia-mcp")
            mockStdioConnectionExists("fetch-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `matches when last connection exists`() {
            mockAnnotationAttributes("brave-search-mcp", "fetch-mcp", "wikipedia-mcp")
            mockStdioConnectionExists("wikipedia-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `matches when all connections exist`() {
            mockAnnotationAttributes("brave-search-mcp", "fetch-mcp", "wikipedia-mcp")
            mockStdioConnectionExists("brave-search-mcp")
            mockStdioConnectionExists("fetch-mcp")
            mockStdioConnectionExists("wikipedia-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `does not match when no connections exist`() {
            mockAnnotationAttributes("brave-search-mcp", "fetch-mcp", "wikipedia-mcp")

            val result = condition.matches(context, metadata)

            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("with mixed connection types")
    inner class MixedConnectionTypes {

        @Test
        fun `matches when stdio exists but sse does not`() {
            mockAnnotationAttributes("github-mcp")
            mockStdioConnectionExists("github-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `matches when sse exists but stdio does not`() {
            mockAnnotationAttributes("github-mcp")
            mockSseConnectionExists("github-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `matches when both stdio and sse exist`() {
            mockAnnotationAttributes("github-mcp")
            mockStdioConnectionExists("github-mcp")
            mockSseConnectionExists("github-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }

        @Test
        fun `matches with multiple connections of different types`() {
            mockAnnotationAttributes("github-mcp", "brave-search-mcp")
            mockStdioConnectionExists("github-mcp")
            mockSseConnectionExists("brave-search-mcp")

            val result = condition.matches(context, metadata)

            assertTrue(result)
        }
    }
}