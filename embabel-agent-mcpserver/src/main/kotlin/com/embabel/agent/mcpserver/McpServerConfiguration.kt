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
package com.embabel.agent.mcpserver

import com.embabel.agent.core.ToolCallbackPublisher
import com.embabel.agent.event.logging.LoggingPersonality.Companion.BANNER_WIDTH
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.loggerFor
import io.modelcontextprotocol.server.McpSyncServer
import org.apache.catalina.util.ServerInfo
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

/**
 * Tag interface extending Spring AI ToolCallbackProvider
 * that identifies tool callbacks that our MCP server exposes.
 */
interface McpToolExportCallbackPublisher : ToolCallbackPublisher, HasInfoString

/**
 * Provides a hello banner for the MCP server.
 */
class BannerTool {

    @Tool(
        name = "Embabel Hello Banner",
        description = "Display a welcome banner with server information"
    )
    fun getHelloBanner(): String {
        val separator = "~".repeat(HELLO_BANNER_WIDTH )
        return "\n${separator}\n" +
                "Embabel Agent MCP server\n" +
                "Server info: ${ServerInfo.getServerInfo()}\n" +
                "Java info: ${System.getProperty("java.runtime.version")}\n" +
                "${separator}\n"
    }

    companion object {
        private const val HELLO_BANNER_WIDTH = 50
    }
}


/**
 * Configures MCP server. Exposes a limited number of tools.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.ANY)
class McpServerConfiguration(
    private val applicationContext: ConfigurableApplicationContext,
) {

    /**
     * Currently MCP Server is configured by AutoConfiguration, which requires
     * at least one ToolCallbackProvider bean to be present in the context in order
     * to build it with Tools Capability.
     *
     * Provides a simple banner tool callback to display a welcome message.
     */
    @Bean
    fun helloBannerCallback(): ToolCallbackProvider {
        return MethodToolCallbackProvider.builder().toolObjects(BannerTool()).build()
    }

    /**
     * Configures and initializes MCP server tool callbacks when the agent scanning process completes.
     *
     * This event-driven approach ensures that all tool callbacks are properly registered only after
     * the application context is fully initialized and all agent beans have been processed and deployed.
     * Without this synchronization, the MCP server might start without access to all available tools.
     */
    @EventListener(AgentScanningBeanPostProcessorEvent::class)
    fun callbacks() {
        val mcpToolExportCallbackPublishers: List<McpToolExportCallbackPublisher> =
            applicationContext.getBeansOfType(McpToolExportCallbackPublisher::class.java).values.toList()
        val allToolCallbacks = mcpToolExportCallbackPublishers.flatMap { it.toolCallbacks }
        val separator = "~".repeat(BANNER_WIDTH)
        loggerFor<McpServerConfiguration>().info(
            "\n${separator}\n{} MCP tool exporters: {}\nExposing a total of {} MCP server tools:\n\t{}\n${separator}",
            mcpToolExportCallbackPublishers.size,
            mcpToolExportCallbackPublishers.map { it.infoString(verbose = true) },
            allToolCallbacks.size,
            allToolCallbacks.joinToString(
                "\n\t"
            ) { "${it.toolDefinition.name()}: ${it.toolDefinition.description()}" }
        )

        // add tool callbacks to MCP server
        val agentTools = McpToolUtils
            .toSyncToolSpecification(allToolCallbacks)

        for (agentTool in agentTools) {
            applicationContext.getBean(McpSyncServer::class.java).addTool(agentTool);
        }

    }

}
