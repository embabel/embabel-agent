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

import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.support.DefaultToolLoop
import com.embabel.agent.spi.support.ObservabilityToolCallback
import com.embabel.agent.spi.support.OutputTransformingToolCallback
import com.embabel.agent.spi.support.springai.SpringToolCallbackAdapter
import com.embabel.agent.spi.support.springai.SpringToolCallbackWrapper
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.ToolCall
import com.embabel.chat.UserMessage
import com.embabel.common.util.StringTransformer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.metadata.DefaultToolMetadata

/**
 * End-to-end tests verifying that [ToolCallContext] flows correctly through
 * the tool execution pipeline: DefaultToolLoop → Tool → Spring AI bridges.
 *
 * All context is passed explicitly — no ThreadLocal is used anywhere in the pipeline.
 *
 * These tests validate the complete implementation of GitHub issue #1323:
 * "Allow metadata to be passed to MCP calls".
 */
class ToolCallContextFlowTest {

    private val objectMapper = jacksonObjectMapper()

    @Nested
    inner class DefaultToolLoopContextFlow {

        @Test
        fun `tool receives context from tool loop`() {
            var receivedContext: ToolCallContext? = null
            val tool = Tool.of(
                name = "ctx_tool",
                description = "Context test",
            ) { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            val context = ToolCallContext.of("authToken" to "bearer-xyz", "tenantId" to "acme")
            val toolLoop = DefaultToolLoop(
                llmMessageSender = singleToolCallThenAnswer("ctx_tool"),
                objectMapper = objectMapper,
                toolCallContext = context,
            )
            toolLoop.execute(
                initialMessages = listOf(UserMessage("go")),
                initialTools = listOf(tool),
                outputParser = { it },
            )
            assertNotNull(receivedContext)
            assertEquals("bearer-xyz", receivedContext!!.get<String>("authToken"))
            assertEquals("acme", receivedContext!!.get<String>("tenantId"))
        }

        @Test
        fun `tool receives EMPTY context when none configured`() {
            var receivedContext: ToolCallContext? = null
            val tool = Tool.of(
                name = "no_ctx_tool",
                description = "No context test",
            ) { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            val toolLoop = DefaultToolLoop(
                llmMessageSender = singleToolCallThenAnswer("no_ctx_tool"),
                objectMapper = objectMapper,
            )
            toolLoop.execute(
                initialMessages = listOf(UserMessage("go")),
                initialTools = listOf(tool),
                outputParser = { it },
            )
            assertNotNull(receivedContext)
            assertTrue(receivedContext!!.isEmpty)
        }

        @Test
        fun `context is available across multiple tool calls`() {
            val captured = mutableListOf<ToolCallContext>()
            val tool = Tool.of(
                name = "multi_tool",
                description = "Multi call test",
            ) { _, context ->
                captured.add(context)
                Tool.Result.text("ok")
            }
            val context = ToolCallContext.of("key" to "value")
            val mockCaller = object : LlmMessageSender {
                private var call = 0
                override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                    call++
                    return when (call) {
                        1 -> toolCallResponse("call_1", "multi_tool", "{}")
                        2 -> toolCallResponse("call_2", "multi_tool", "{}")
                        else -> textResponse("done")
                    }
                }
            }
            val toolLoop = DefaultToolLoop(
                llmMessageSender = mockCaller,
                objectMapper = objectMapper,
                toolCallContext = context,
            )
            toolLoop.execute(
                initialMessages = listOf(UserMessage("go")),
                initialTools = listOf(tool),
                outputParser = { it },
            )
            assertEquals(2, captured.size)
            captured.forEach { assertEquals("value", it.get<String>("key")) }
        }
    }

    @Nested
    inner class DefaultCallBehavior {

        @Test
        fun `default two-arg call discards context for legacy tools`() {
            var singleArgCalled = false
            val legacyTool = object : Tool {
                override val definition = Tool.Definition("legacy", "Legacy tool", Tool.InputSchema.empty())
                override fun call(input: String): Tool.Result {
                    singleArgCalled = true
                    return Tool.Result.text("legacy-ok")
                }
            }
            val ctx = ToolCallContext.of("secret" to "42")
            val result = legacyTool.call("{}", ctx)
            assertTrue(singleArgCalled, "Single-arg call should be invoked via default delegation")
            assertTrue(result is Tool.Result.Text)
            assertEquals("legacy-ok", (result as Tool.Result.Text).content)
        }

        @Test
        fun `context-aware tools receive context explicitly`() {
            var receivedContext: ToolCallContext? = null
            val tool = Tool.of(
                name = "aware_tool",
                description = "Context-aware",
            ) { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            val ctx = ToolCallContext.of("authToken" to "bearer-abc")
            tool.call("""{"query":"test"}""", ctx)
            assertEquals("bearer-abc", receivedContext!!.get<String>("authToken"))
        }

        @Test
        fun `context-aware tool receives EMPTY context via single-arg call`() {
            var receivedContext: ToolCallContext? = null
            val tool = Tool.of(
                name = "no_ctx_tool",
                description = "No context",
            ) { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            // Single-arg call — context-aware factory wraps with EMPTY
            tool.call("{}")
            assertNotNull(receivedContext)
            assertTrue(receivedContext!!.isEmpty)
        }
    }

    @Nested
    inner class SpringToolCallbackWrapperContextBridging {

        @Test
        fun `wrapper bridges ToolCallContext to Spring AI ToolContext`() {
            var receivedToolContext: ToolContext? = null
            val springCallback = object : ToolCallback {
                override fun getToolDefinition() = DefaultToolDefinition.builder()
                    .name("mcp_tool").description("MCP tool").inputSchema("{}").build()
                override fun getToolMetadata() = DefaultToolMetadata.builder().build()
                override fun call(toolInput: String) = "no-context"
                override fun call(toolInput: String, toolContext: ToolContext?): String {
                    receivedToolContext = toolContext
                    return "with-context"
                }
            }
            val wrapper = SpringToolCallbackWrapper(springCallback)
            val ctx = ToolCallContext.of("authToken" to "xyz", "tenantId" to "acme")
            val result = wrapper.call("{}", ctx)
            assertTrue(result is Tool.Result.Text)
            assertEquals("with-context", (result as Tool.Result.Text).content)
            assertNotNull(receivedToolContext)
            assertEquals("xyz", receivedToolContext!!.context["authToken"])
            assertEquals("acme", receivedToolContext!!.context["tenantId"])
        }

        @Test
        fun `wrapper calls without ToolContext when context is empty`() {
            var calledWithContext = false
            val springCallback = object : ToolCallback {
                override fun getToolDefinition() = DefaultToolDefinition.builder()
                    .name("test").description("").inputSchema("{}").build()
                override fun getToolMetadata() = DefaultToolMetadata.builder().build()
                override fun call(toolInput: String): String {
                    calledWithContext = false
                    return "no-context"
                }
                override fun call(toolInput: String, toolContext: ToolContext?): String {
                    calledWithContext = true
                    return "with-context"
                }
            }
            val wrapper = SpringToolCallbackWrapper(springCallback)
            wrapper.call("{}", ToolCallContext.EMPTY)
            assertFalse(calledWithContext)
        }

        @Test
        fun `single-arg call does not bridge context`() {
            var receivedToolContext: ToolContext? = null
            val springCallback = object : ToolCallback {
                override fun getToolDefinition() = DefaultToolDefinition.builder()
                    .name("test").description("").inputSchema("{}").build()
                override fun getToolMetadata() = DefaultToolMetadata.builder().build()
                override fun call(toolInput: String): String {
                    // Single-arg — no context expected
                    return "single-arg"
                }
                override fun call(toolInput: String, toolContext: ToolContext?): String {
                    receivedToolContext = toolContext
                    return "two-arg"
                }
            }
            val wrapper = SpringToolCallbackWrapper(springCallback)
            val result = wrapper.call("{}")
            assertTrue(result is Tool.Result.Text)
            assertEquals("single-arg", (result as Tool.Result.Text).content)
            // Two-arg variant should NOT have been called
            assertNull(receivedToolContext)
        }
    }

    @Nested
    inner class SpringToolCallbackAdapterContextBridging {

        @Test
        fun `adapter passes ToolContext to two-arg tool call`() {
            var receivedContext: ToolCallContext? = null
            val tool = Tool.of("ctx_tool", "Context test") { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            val adapter = SpringToolCallbackAdapter(tool)
            val springCtx = ToolContext(mapOf("from-spring" to "spring-val"))
            adapter.call("{}", springCtx)
            assertNotNull(receivedContext)
            assertEquals("spring-val", receivedContext!!.get<String>("from-spring"))
        }

        @Test
        fun `adapter passes EMPTY when no ToolContext provided`() {
            var receivedContext: ToolCallContext? = null
            val tool = Tool.of("no_ctx_tool", "No context") { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            val adapter = SpringToolCallbackAdapter(tool)
            adapter.call("{}", null)
            assertNotNull(receivedContext)
            assertTrue(receivedContext!!.isEmpty)
        }
    }

    @Nested
    inner class EndToEndMcpSimulation {

        @Test
        fun `context flows from DefaultToolLoop through SpringToolCallbackWrapper to ToolCallback`() {
            var mcpReceivedContext: ToolContext? = null
            // Simulate an MCP ToolCallback that expects ToolContext (like McpMeta)
            val mcpCallback = object : ToolCallback {
                override fun getToolDefinition() = DefaultToolDefinition.builder()
                    .name("mcp_search").description("MCP search").inputSchema("{}").build()
                override fun getToolMetadata() = DefaultToolMetadata.builder().build()
                override fun call(toolInput: String) = "no-context"
                override fun call(toolInput: String, toolContext: ToolContext?): String {
                    mcpReceivedContext = toolContext
                    return """{"results": ["found"]}"""
                }
            }
            // Wrap as Embabel Tool (this is what SpringAiMcpToolFactory does)
            val tool = SpringToolCallbackWrapper(mcpCallback)
            val context = ToolCallContext.of("authToken" to "bearer-secret", "userId" to "user-42")
            val toolLoop = DefaultToolLoop(
                llmMessageSender = singleToolCallThenAnswer("mcp_search"),
                objectMapper = objectMapper,
                toolCallContext = context,
            )
            val result = toolLoop.execute(
                initialMessages = listOf(UserMessage("search for something")),
                initialTools = listOf(tool),
                outputParser = { it },
            )
            assertNotNull(mcpReceivedContext)
            assertEquals("bearer-secret", mcpReceivedContext!!.context["authToken"])
            assertEquals("user-42", mcpReceivedContext!!.context["userId"])
            assertEquals("done", result.result)
        }
    }

    @Nested
    inner class DelegatingToolContextForwarding {

        @Test
        fun `renamed tool forwards context to delegate`() {
            var receivedContext: ToolCallContext? = null
            val inner = Tool.of("original", "test") { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            val renamed = inner.withName("renamed_tool")
            val ctx = ToolCallContext.of("key" to "value")
            renamed.call("{}", ctx)
            assertNotNull(receivedContext)
            assertEquals("value", receivedContext!!.get<String>("key"))
        }

        @Test
        fun `described tool forwards context to delegate`() {
            var receivedContext: ToolCallContext? = null
            val inner = Tool.of("original", "test") { _, context ->
                receivedContext = context
                Tool.Result.text("ok")
            }
            val described = inner.withDescription("new description")
            val ctx = ToolCallContext.of("key" to "value")
            described.call("{}", ctx)
            assertNotNull(receivedContext)
            assertEquals("value", receivedContext!!.get<String>("key"))
        }
    }

    @Nested
    inner class ObservabilityToolCallbackContextForwarding {

        @Test
        fun `observability callback forwards ToolContext to delegate`() {
            var receivedToolContext: ToolContext? = null
            val delegate = object : ToolCallback {
                override fun getToolDefinition() = DefaultToolDefinition.builder()
                    .name("obs_tool").description("").inputSchema("{}").build()
                override fun call(toolInput: String) = "no-context"
                override fun call(toolInput: String, toolContext: ToolContext?): String {
                    receivedToolContext = toolContext
                    return "observed"
                }
            }
            val observed = ObservabilityToolCallback(delegate, ObservationRegistry.NOOP)
            val ctx = ToolContext(mapOf("key" to "val"))
            val result = observed.call("{}", ctx)
            assertEquals("observed", result)
            assertNotNull(receivedToolContext)
            assertEquals("val", receivedToolContext!!.context["key"])
        }
    }

    @Nested
    inner class OutputTransformingToolCallbackContextForwarding {

        @Test
        fun `output transforming callback forwards ToolContext to delegate`() {
            var receivedToolContext: ToolContext? = null
            val delegate = object : ToolCallback {
                override fun getToolDefinition() = DefaultToolDefinition.builder()
                    .name("xform_tool").description("").inputSchema("{}").build()
                override fun call(toolInput: String) = "no-context"
                override fun call(toolInput: String, toolContext: ToolContext?): String {
                    receivedToolContext = toolContext
                    return "UPPER result"
                }
            }
            val transformer = StringTransformer { it.lowercase() }
            val xform = OutputTransformingToolCallback(delegate, transformer)
            val ctx = ToolContext(mapOf("key" to "val"))
            val result = xform.call("{}", ctx)
            assertEquals("upper result", result)
            assertNotNull(receivedToolContext)
            assertEquals("val", receivedToolContext!!.context["key"])
        }
    }

    // -- Helpers --

    private fun singleToolCallThenAnswer(toolName: String): LlmMessageSender {
        return object : LlmMessageSender {
            private var called = false
            override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
                if (!called) {
                    called = true
                    return toolCallResponse("call_1", toolName, "{}")
                }
                return textResponse("done")
            }
        }
    }

    private fun toolCallResponse(id: String, name: String, arguments: String) = LlmMessageResponse(
        message = AssistantMessageWithToolCalls(
            content = " ",
            toolCalls = listOf(ToolCall(id, name, arguments)),
        ),
        textContent = "",
    )

    private fun textResponse(text: String) = LlmMessageResponse(
        message = AssistantMessage(text),
        textContent = text,
    )
}
