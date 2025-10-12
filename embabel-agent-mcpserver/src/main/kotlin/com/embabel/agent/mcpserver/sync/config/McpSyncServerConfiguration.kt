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
package com.embabel.agent.mcpserver.sync.config

import com.embabel.agent.mcpserver.*
import com.embabel.agent.mcpserver.domain.McpExecutionMode
import com.embabel.agent.mcpserver.sync.McpPromptPublisher
import com.embabel.agent.mcpserver.sync.McpResourcePublisher
import com.embabel.agent.mcpserver.sync.SyncServerStrategy
import io.modelcontextprotocol.server.McpSyncServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.*
import org.springframework.core.type.AnnotatedTypeMetadata


/**
 * Spring condition to determine if the MCP sync server configuration should be activated.
 *
 * Checks if the `embabel.agent.mcpserver.enabled` property is `true` and
 * the `embabel.agent.mcpserver.type` property is set to `"SYNC"`.
 */
class McpSyncServerCondition : Condition {

    /**
     * Evaluates the condition based on the application environment.
     *
     * @param context the condition context containing environment and bean factory
     * @param metadata annotation metadata for the condition
     * @return `true` if sync server should be enabled, otherwise `false`
     */
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata
    ): Boolean {
        val environment = context.environment
        val type = environment.getProperty("spring.ai.mcp.server.type", "SYNC")
        return type == "SYNC"
    }
}

/**
 * Spring configuration for the MCP sync server.
 *
 * Uses the template method pattern to provide sync-specific server strategy,
 * tool, resource, and prompt publishers, and tool specification conversion.
 *
 * @param applicationContext the Spring application context
 */
@Configuration
@Conditional(McpSyncServerCondition::class)
class McpSyncServerConfiguration(
    applicationContext: ConfigurableApplicationContext,
) : AbstractMcpServerConfiguration(applicationContext) {

    override val logger: Logger = LoggerFactory.getLogger(McpSyncServerConfiguration::class.java)

    /**
     * Information about the current server instance.
     */
    private val serverInfo = ServerInfoFactory.create(McpExecutionMode.SYNC)

    /**
     * Registers the banner tool callback bean for the sync server.
     *
     * @return a `ToolCallbackProvider` for the banner tool
     */
    @Bean
    fun syncBannerCallback(): ToolCallbackProvider = createBannerTool()

    /**
     * Creates the sync server strategy.
     *
     * @return a `McpServerStrategy` for synchronous execution
     */
    override fun createServerStrategy(): McpServerStrategy {
        val syncServer = applicationContext.getBean(McpSyncServer::class.java)
        return SyncServerStrategy(syncServer)
    }

    /**
     * Creates the banner tool callback provider for the sync server.
     *
     * @return a `ToolCallbackProvider` for the banner tool
     */
    override fun createBannerTool(): ToolCallbackProvider {
        return MethodToolCallbackProvider.builder()
            .toolObjects(UnifiedBannerTool(serverInfo))
            .build()
    }

    /**
     * Retrieves all tool publishers registered in the application context.
     *
     * @return a list of `McpToolExportCallbackPublisher` beans
     */
    override fun getToolPublishers(): List<McpToolExportCallbackPublisher> {
        return applicationContext.getBeansOfType(McpToolExportCallbackPublisher::class.java).values.toList()
    }

    /**
     * Retrieves all resource publishers registered in the application context.
     *
     * @return a list of `McpResourcePublisher` beans
     */
    override fun getResourcePublishers(): List<McpResourcePublisher> {
        return applicationContext.getBeansOfType(McpResourcePublisher::class.java).values.toList()
    }

    /**
     * Retrieves all prompt publishers registered in the application context.
     *
     * @return a list of `McpPromptPublisher` beans
     */
    override fun getPromptPublishers(): List<McpPromptPublisher> {
        return applicationContext.getBeansOfType(McpPromptPublisher::class.java).values.toList()
    }

    /**
     * Converts tool callback objects to sync tool specifications.
     *
     * @param toolCallbacks a list of tool callback objects
     * @return a list of sync tool specifications
     */
    override fun convertToToolSpecifications(toolCallbacks: List<Any>): List<Any> {
        // Cast to the expected ToolCallback type for McpToolUtils
        val callbacks = toolCallbacks.filterIsInstance<org.springframework.ai.tool.ToolCallback>()
        return McpToolUtils.toSyncToolSpecification(callbacks)
    }

    /**
     * Returns the execution mode for this configuration.
     *
     * @return the string `"SYNC"`
     */
    override fun getExecutionMode(): String = "SYNC"
}
