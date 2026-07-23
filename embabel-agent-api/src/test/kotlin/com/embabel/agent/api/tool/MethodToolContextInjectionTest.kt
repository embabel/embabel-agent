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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.LlmTool.Param
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests verifying that [MethodTool] (both Kotlin and Java variants) correctly
 * handles [ToolCallContext] injection into `@LlmTool`-annotated methods.
 *
 * Covers:
 * - Context is injected when method declares a [ToolCallContext] parameter
 * - Context parameter is excluded from the JSON input schema sent to the LLM
 * - Methods without [ToolCallContext] parameter continue to work (backward compat)
 * - EMPTY context is injected when no context is provided
 * - Context works alongside regular parameters
 */
class MethodToolContextInjectionTest {

    private val objectMapper = jacksonObjectMapper()

    // ---- Kotlin test fixtures ----

    class ContextAwareTools {
        var lastContext: ToolCallContext? = null

        @LlmTool(description = "Search with auth context")
        fun search(
            @Param(description = "Search query") query: String,
            context: ToolCallContext,
        ): String {
            lastContext = context
            val token = context.get<String>("authToken") ?: "none"
            return "Results for '$query' with token=$token"
        }
    }

    class ContextOnlyTool {
        var lastContext: ToolCallContext? = null

        @LlmTool(description = "Tool that only takes context")
        fun audit(context: ToolCallContext): String {
            lastContext = context
            val userId = context.get<String>("userId") ?: "anonymous"
            return "Audit logged for $userId"
        }
    }

    class NoContextTools {
        @LlmTool(description = "Simple greeting")
        fun greet(@Param(description = "Name to greet") name: String): String {
            return "Hello, $name!"
        }
    }

    class MultiParamWithContext {
        var lastContext: ToolCallContext? = null

        @LlmTool(description = "Transfer funds between accounts")
        fun transfer(
            @Param(description = "Source account") from: String,
            @Param(description = "Destination account") to: String,
            @Param(description = "Amount to transfer") amount: Int,
            context: ToolCallContext,
        ): String {
            lastContext = context
            val tenantId = context.get<String>("tenantId") ?: "unknown"
            return "Transferred $amount from $from to $to (tenant=$tenantId)"
        }
    }

    class ContextWithOptionalParam {
        var lastContext: ToolCallContext? = null

        @LlmTool(description = "Fetch data with optional format")
        fun fetch(
            @Param(description = "Resource ID") id: String,
            @Param(description = "Response format", required = false) format: String = "json",
            context: ToolCallContext,
        ): String {
            lastContext = context
            return "Fetched $id as $format"
        }
    }

    @Nested
    inner class KotlinContextInjection {

        @Test
        fun `context is injected into method with ToolCallContext parameter`() {
            val instance = ContextAwareTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val ctx = ToolCallContext.of("authToken" to "bearer-secret-123")
            val result = tool.call("""{"query":"embabel agent"}""", ctx)

            assertTrue(result is Tool.Result.Text)
            assertEquals(
                "Results for 'embabel agent' with token=bearer-secret-123",
                (result as Tool.Result.Text).content,
            )
            assertNotNull(instance.lastContext)
            assertEquals("bearer-secret-123", instance.lastContext!!.get<String>("authToken"))
        }

        @Test
        fun `EMPTY context is injected when single-arg call is used`() {
            val instance = ContextAwareTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val result = tool.call("""{"query":"test"}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals(
                "Results for 'test' with token=none",
                (result as Tool.Result.Text).content,
            )
            assertNotNull(instance.lastContext)
            assertTrue(instance.lastContext!!.isEmpty)
        }

        @Test
        fun `method with only ToolCallContext parameter receives context`() {
            val instance = ContextOnlyTool()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val ctx = ToolCallContext.of("userId" to "user-42")
            val result = tool.call("{}", ctx)

            assertTrue(result is Tool.Result.Text)
            assertEquals("Audit logged for user-42", (result as Tool.Result.Text).content)
            assertEquals("user-42", instance.lastContext!!.get<String>("userId"))
        }

        @Test
        fun `method without ToolCallContext parameter works normally`() {
            val instance = NoContextTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val result = tool.call("""{"name":"Claude"}""")

            assertTrue(result is Tool.Result.Text)
            assertEquals("Hello, Claude!", (result as Tool.Result.Text).content)
        }

        @Test
        fun `method without ToolCallContext parameter ignores provided context`() {
            val instance = NoContextTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val ctx = ToolCallContext.of("authToken" to "should-be-ignored")
            val result = tool.call("""{"name":"Claude"}""", ctx)

            assertTrue(result is Tool.Result.Text)
            assertEquals("Hello, Claude!", (result as Tool.Result.Text).content)
        }

        @Test
        fun `context works alongside multiple regular parameters`() {
            val instance = MultiParamWithContext()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val ctx = ToolCallContext.of("tenantId" to "acme-corp")
            val result = tool.call(
                """{"from":"checking","to":"savings","amount":500}""",
                ctx,
            )

            assertTrue(result is Tool.Result.Text)
            assertEquals(
                "Transferred 500 from checking to savings (tenant=acme-corp)",
                (result as Tool.Result.Text).content,
            )
            assertEquals("acme-corp", instance.lastContext!!.get<String>("tenantId"))
        }

        @Test
        fun `context works with optional parameters using default values`() {
            val instance = ContextWithOptionalParam()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val ctx = ToolCallContext.of("traceId" to "trace-abc")
            val result = tool.call("""{"id":"resource-1"}""", ctx)

            assertTrue(result is Tool.Result.Text)
            assertEquals("Fetched resource-1 as json", (result as Tool.Result.Text).content)
            assertNotNull(instance.lastContext)
        }

        @Test
        fun `context with multiple entries is fully available in method`() {
            val instance = ContextAwareTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val ctx = ToolCallContext.of(
                "authToken" to "bearer-xyz",
                "tenantId" to "acme",
                "correlationId" to "req-123",
            )
            tool.call("""{"query":"test"}""", ctx)

            val captured = instance.lastContext!!
            assertEquals("bearer-xyz", captured.get<String>("authToken"))
            assertEquals("acme", captured.get<String>("tenantId"))
            assertEquals("req-123", captured.get<String>("correlationId"))
        }
    }

    @Nested
    inner class SchemaExclusion {

        @Test
        fun `ToolCallContext parameter is excluded from input schema`() {
            val instance = ContextAwareTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val schema = tool.definition.inputSchema.toJsonSchema()
            val schemaMap = objectMapper.readValue(schema, Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val properties = schemaMap["properties"] as Map<String, Any>

            assertTrue("query" in properties, "Schema should include 'query' parameter")
            assertFalse("context" in properties, "Schema must NOT include 'context' (ToolCallContext) parameter")
        }

        @Test
        fun `schema has correct required fields excluding ToolCallContext`() {
            val instance = ContextAwareTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val schema = tool.definition.inputSchema.toJsonSchema()
            val schemaMap = objectMapper.readValue(schema, Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val required = schemaMap["required"] as? List<String> ?: emptyList()

            assertTrue("query" in required, "'query' should be required")
            assertFalse("context" in required, "'context' must NOT appear in required")
        }

        @Test
        fun `schema for context-only tool has no parameters`() {
            val instance = ContextOnlyTool()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val schema = tool.definition.inputSchema.toJsonSchema()
            val schemaMap = objectMapper.readValue(schema, Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val properties = schemaMap["properties"] as? Map<String, Any> ?: emptyMap()

            assertTrue(properties.isEmpty(), "Schema should have no properties when only ToolCallContext is declared")
        }

        @Test
        fun `schema for multi-param tool excludes only ToolCallContext`() {
            val instance = MultiParamWithContext()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val schema = tool.definition.inputSchema.toJsonSchema()
            val schemaMap = objectMapper.readValue(schema, Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val properties = schemaMap["properties"] as Map<String, Any>

            assertEquals(3, properties.size, "Should have exactly 3 parameters (from, to, amount)")
            assertTrue("from" in properties)
            assertTrue("to" in properties)
            assertTrue("amount" in properties)
            assertFalse("context" in properties, "ToolCallContext must be excluded")
        }

        @Test
        fun `parameter count matches schema parameters excluding context`() {
            val instance = ContextAwareTools()
            val tools = Tool.fromInstance(instance, objectMapper)
            val tool = tools.single()

            val params = tool.definition.inputSchema.parameters
            assertEquals(1, params.size, "Should have 1 parameter (query only, not context)")
            assertEquals("query", params[0].name)
        }
    }
}
