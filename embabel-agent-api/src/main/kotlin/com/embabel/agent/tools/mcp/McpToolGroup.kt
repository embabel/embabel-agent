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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.agent.spi.support.springai.toEmbabelTool
import com.embabel.common.core.types.Semver
import com.embabel.common.util.loggerFor
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback

/**
 * ToolGroup backed by MCP.
 *
 * Tools are loaded **lazily** on first access rather than at construction time.
 * This is required when MCP clients are configured with
 * `spring.ai.mcp.client.initialized=false` so that the user's OAuth token
 * is present in the security context when the MCP handshake occurs.
 *
 * Kotlin's [lazy] delegate uses [LazyThreadSafetyMode.SYNCHRONIZED] by default,
 * so concurrent first-access across threads is safe without additional locking.
 *
 * @param description Description of the tool group
 * @param provider Name of the provider of the tool group
 * @param name Name of the tool group
 * @param permissions Permissions the tools requires
 * @param clients List of MCP clients to use to load tools
 * @param filter predicate that returns true to include a tool
 */
class McpToolGroup(
    description: ToolGroupDescription,
    provider: String,
    name: String,
    permissions: Set<ToolGroupPermission>,
    private val clients: List<McpSyncClient>,
    private val filter: ((ToolCallback) -> Boolean),
    private val metaConverter: ToolCallContextMcpMetaConverter = ToolCallContextMcpMetaConverter.passThrough(),
) : ToolGroup {

    override val metadata: ToolGroupMetadata = ToolGroupMetadata(
        description = description,
        name = name,
        provider = provider,
        version = Semver(0, 1, 0),
        permissions = permissions,
    )

    /**
     * Backing delegate kept as a named field so [isInitialized] can be checked
     * directly — without reflection — in [toString] and any future diagnostics.
     */
    private val toolsDelegate: Lazy<List<Tool>> = lazy { loadTools() }

    /**
     * Lazily loaded tool list. The first call to this property triggers the MCP
     * client handshake (listTools), which happens during action execution when
     * the user's security context — and therefore their OAuth token — is available.
     *
     * The result is cached by [toolsDelegate]; subsequent accesses within the same
     * JVM lifetime return the cached list without additional MCP traffic.
     */
    override val tools: List<Tool> get() = toolsDelegate.value

    private fun loadTools(): List<Tool> {
        return try {
            val provider = SyncMcpToolCallbackProvider(clients)
            // Filter the raw callbacks, then convert to native Tool
            val filteredCallbacks = provider.toolCallbacks.filter(filter)
            val nativeTools = filteredCallbacks.map { it.toEmbabelTool(metaConverter) }
            loggerFor<McpToolGroup>().debug(
                "ToolGroup role={}: {}",
                metadata.role,
                nativeTools.map { it.definition.name },
            )
            nativeTools
        } catch (e: Exception) {
            loggerFor<McpToolGroup>().error(
                "Failed to load tools for role {}: {}",
                metadata.role,
                e.message,
            )
            emptyList()
        }
    }

    override fun toString(): String =
        "McpToolGroup(metadata=$metadata, toolsInitialized=${toolsDelegate.isInitialized()})"
}
