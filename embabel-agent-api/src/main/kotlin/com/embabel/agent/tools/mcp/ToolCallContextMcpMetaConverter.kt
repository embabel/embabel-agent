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

/**
 * Converts a [ToolCallContext] into MCP `_meta` metadata for outbound MCP tool calls.
 *
 * This is the gateway filter that controls what context entries cross the process boundary
 * when Embabel calls tools on remote MCP servers. Think of it like an HTTP header filter
 * on a reverse proxy: the converter decides which entries are safe and relevant to propagate
 * to a third-party server, and which should stay local.
 *
 * ## Why This Matters
 *
 * A [ToolCallContext] may carry sensitive entries (API keys, auth tokens, tenant secrets)
 * alongside benign metadata (tenant IDs, correlation IDs, user preferences). Without filtering,
 * all entries would be sent as MCP `_meta` to every MCP server — including untrusted third-party
 * servers that should never see secrets.
 *
 * ## Usage
 *
 * The converter is used by [com.embabel.agent.spi.support.springai.SpringToolCallbackWrapper]
 * when bridging Embabel tools to Spring AI MCP callbacks.
 *
 * ### Default behavior
 *
 * The [passThrough] converter propagates all entries. Use this only when all MCP servers
 * are trusted (e.g., internal infrastructure).
 *
 * ### Allowlist approach (recommended for production)
 * ```kotlin
 * val converter = ToolCallContextMcpMetaConverter.allowKeys("tenantId", "correlationId", "locale")
 * ```
 *
 * ### Denylist approach
 * ```kotlin
 * val converter = ToolCallContextMcpMetaConverter.denyKeys("apiKey", "secretToken", "authHeader")
 * ```
 *
 * ### Custom logic
 * ```kotlin
 * val converter = ToolCallContextMcpMetaConverter { context ->
 *     mapOf(
 *         "tenantId" to (context.get<String>("tenantId") ?: "unknown"),
 *         "requestedAt" to Instant.now().toString(),
 *     )
 * }
 * ```
 *
 * ### Spring Bean (applied globally)
 * ```kotlin
 * @Bean
 * fun toolCallContextMcpMetaConverter() =
 *     ToolCallContextMcpMetaConverter.allowKeys("tenantId", "correlationId")
 * ```
 *
 * If no bean is defined, the framework defaults to [passThrough] for backward compatibility.
 *
 * @see com.embabel.agent.api.tool.ToolCallContext
 */
fun interface ToolCallContextMcpMetaConverter {

    /**
     * Convert a [ToolCallContext] to a metadata map suitable for MCP `_meta`.
     *
     * @param context The full tool call context from the current execution
     * @return A filtered/transformed map of metadata to send with the MCP tool call.
     *         An empty map means no metadata will be attached.
     */
    fun convert(context: ToolCallContext): Map<String, Any>

    companion object {

        /**
         * Converter that propagates all context entries as MCP metadata.
         * Use only when all MCP servers are trusted.
         */
        @JvmStatic
        fun passThrough(): ToolCallContextMcpMetaConverter =
            ToolCallContextMcpMetaConverter { it.toMap() }

        /**
         * Converter that suppresses all context — no metadata is sent to MCP servers.
         */
        @JvmStatic
        fun noOp(): ToolCallContextMcpMetaConverter =
            ToolCallContextMcpMetaConverter { emptyMap() }

        /**
         * Converter that only propagates entries whose keys are in the allowlist.
         * This is the recommended approach for production: explicitly declare
         * what crosses the boundary.
         *
         * @param keys The keys to allow through
         */
        @JvmStatic
        fun allowKeys(vararg keys: String): ToolCallContextMcpMetaConverter {
            val allowed = keys.toSet()
            return ToolCallContextMcpMetaConverter { context ->
                context.toMap().filterKeys { it in allowed }
            }
        }

        /**
         * Converter that propagates all entries except those whose keys match the denylist.
         *
         * @param keys The keys to exclude
         */
        @JvmStatic
        fun denyKeys(vararg keys: String): ToolCallContextMcpMetaConverter {
            val denied = keys.toSet()
            return ToolCallContextMcpMetaConverter { context ->
                context.toMap().filterKeys { it !in denied }
            }
        }
    }
}
