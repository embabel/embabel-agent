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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.Tool
import io.mockk.mockk
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.execution.ToolExecutionException
import java.io.IOException

/**
 * Proves that a failing Embabel tool comes out the other side of the MCP wire as
 * `CallToolResult.isError == true`, fixing issue #1780 where every failure looked
 * like a successful call whose text happened to start with "ERROR:".
 *
 * Goes through the real [McpToolUtils.toSyncToolSpecification] handler, the same
 * code Spring AI's MCP server support uses to turn a [ToolExecutionException]
 * into an error [McpSchema.CallToolResult].
 */
class McpToolErrorMappingTest {

    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)

    private fun request(name: String) =
        McpSchema.CallToolRequest.builder().name(name).arguments(emptyMap<String, Any>()).build()

    private fun textOf(result: McpSchema.CallToolResult): String =
        (result.content().first() as McpSchema.TextContent).text()

    @Test
    fun `tool throwing a RuntimeException surfaces as an MCP error`() {
        val tool = Tool.of(name = "boom", description = "Throws") { _ ->
            throw RuntimeException("access denied")
        }
        val spec = McpToolUtils.toSyncToolSpecification(SpringToolCallbackAdapter(tool))

        val result = spec.callHandler().apply(exchange, request("boom"))

        assertTrue(result.isError())
        assertEquals("access denied", textOf(result))
    }

    @Test
    fun `tool returning Result-Error surfaces as an MCP error`() {
        val tool = Tool.of(name = "soft_fail", description = "Fails softly") { _ ->
            Tool.Result.error("soft failure")
        }
        val spec = McpToolUtils.toSyncToolSpecification(SpringToolCallbackAdapter(tool))

        val result = spec.callHandler().apply(exchange, request("soft_fail"))

        assertTrue(result.isError())
        assertEquals("soft failure", textOf(result))
    }

    @Test
    fun `tool throwing a checked exception wraps it in a RuntimeException cause`() {
        val tool = Tool.of(name = "io_fail", description = "Throws checked") { _ ->
            throw IOException("disk gone")
        }
        val callback = SpringToolCallbackAdapter(tool)

        val thrown = assertThrows<ToolExecutionException> { callback.call("{}") }
        assertTrue(thrown.cause is RuntimeException)
        assertEquals("disk gone", thrown.message)

        val spec = McpToolUtils.toSyncToolSpecification(callback)
        val result = spec.callHandler().apply(exchange, request("io_fail"))

        assertTrue(result.isError())
        assertEquals("disk gone", textOf(result))
    }

    @Test
    fun `happy path is unaffected`() {
        val tool = Tool.of(name = "ok", description = "Succeeds") { _ ->
            Tool.Result.text("ok")
        }
        val spec = McpToolUtils.toSyncToolSpecification(SpringToolCallbackAdapter(tool))

        val result = spec.callHandler().apply(exchange, request("ok"))

        assertFalse(result.isError() == true)
        assertEquals("ok", textOf(result))
    }
}
