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
package com.embabel.agent.tools.mcp

import com.embabel.agent.api.tool.ToolCallContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolCallContextMcpMetaConverterTest {

    private val context = ToolCallContext.of(
        "authToken" to "bearer-secret",
        "tenantId" to "acme",
        "correlationId" to "req-123",
        "apiKey" to "sk-supersecret",
    )

    @Nested
    inner class PassThrough {

        @Test
        fun `propagates all entries`() {
            val converter = ToolCallContextMcpMetaConverter.passThrough()
            val result = converter.convert(context)
            assertEquals(context.toMap(), result)
        }

        @Test
        fun `returns empty map for EMPTY context`() {
            val converter = ToolCallContextMcpMetaConverter.passThrough()
            val result = converter.convert(ToolCallContext.EMPTY)
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class NoOp {

        @Test
        fun `suppresses all entries`() {
            val converter = ToolCallContextMcpMetaConverter.noOp()
            val result = converter.convert(context)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `returns empty map for EMPTY context`() {
            val converter = ToolCallContextMcpMetaConverter.noOp()
            val result = converter.convert(ToolCallContext.EMPTY)
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class AllowKeys {

        @Test
        fun `only propagates allowed keys`() {
            val converter = ToolCallContextMcpMetaConverter.allowKeys("tenantId", "correlationId")
            val result = converter.convert(context)
            assertEquals(
                mapOf("tenantId" to "acme", "correlationId" to "req-123"),
                result,
            )
        }

        @Test
        fun `returns empty map when no keys match`() {
            val converter = ToolCallContextMcpMetaConverter.allowKeys("nonExistent")
            val result = converter.convert(context)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `returns empty map for EMPTY context`() {
            val converter = ToolCallContextMcpMetaConverter.allowKeys("tenantId")
            val result = converter.convert(ToolCallContext.EMPTY)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `single key allowlist works`() {
            val converter = ToolCallContextMcpMetaConverter.allowKeys("authToken")
            val result = converter.convert(context)
            assertEquals(mapOf("authToken" to "bearer-secret"), result)
        }
    }

    @Nested
    inner class DenyKeys {

        @Test
        fun `excludes denied keys`() {
            val converter = ToolCallContextMcpMetaConverter.denyKeys("authToken", "apiKey")
            val result = converter.convert(context)
            assertEquals(
                mapOf("tenantId" to "acme", "correlationId" to "req-123"),
                result,
            )
        }

        @Test
        fun `propagates all when no keys match denylist`() {
            val converter = ToolCallContextMcpMetaConverter.denyKeys("nonExistent")
            val result = converter.convert(context)
            assertEquals(context.toMap(), result)
        }

        @Test
        fun `returns empty map for EMPTY context`() {
            val converter = ToolCallContextMcpMetaConverter.denyKeys("authToken")
            val result = converter.convert(ToolCallContext.EMPTY)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `denying all keys produces empty map`() {
            val converter = ToolCallContextMcpMetaConverter.denyKeys(
                "authToken", "tenantId", "correlationId", "apiKey",
            )
            val result = converter.convert(context)
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class CustomConverter {

        @Test
        fun `lambda converter can transform entries`() {
            val converter = ToolCallContextMcpMetaConverter { ctx ->
                mapOf(
                    "tenant" to (ctx.get<String>("tenantId") ?: "unknown"),
                    "hasAuth" to (ctx.get<String>("authToken") != null).toString(),
                )
            }
            val result = converter.convert(context)
            assertEquals(
                mapOf("tenant" to "acme", "hasAuth" to "true"),
                result,
            )
        }

        @Test
        fun `lambda converter can return empty map`() {
            val converter = ToolCallContextMcpMetaConverter { emptyMap() }
            val result = converter.convert(context)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `lambda converter receives full context`() {
            var receivedContext: ToolCallContext? = null
            val converter = ToolCallContextMcpMetaConverter { ctx ->
                receivedContext = ctx
                emptyMap()
            }
            converter.convert(context)
            assertNotNull(receivedContext)
            assertEquals("bearer-secret", receivedContext!!.get<String>("authToken"))
        }
    }

    @Nested
    inner class AllowVsDenySymmetry {

        @Test
        fun `allowKeys and denyKeys are complementary for full key set`() {
            val allowResult = ToolCallContextMcpMetaConverter
                .allowKeys("tenantId", "correlationId")
                .convert(context)
            val denyResult = ToolCallContextMcpMetaConverter
                .denyKeys("authToken", "apiKey")
                .convert(context)
            assertEquals(allowResult, denyResult)
        }
    }
}
