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
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration


/**
 * Condition classes for determining which configuration to activate.
 */
class McpSyncServerCondition : org.springframework.context.annotation.Condition {
    override fun matches(
        context: org.springframework.context.annotation.ConditionContext,
        metadata: org.springframework.core.type.AnnotatedTypeMetadata
    ): Boolean {
        val environment = context.environment
        val enabled = environment.getProperty("embabel.agent.mcpserver.enabled", Boolean::class.java, false)
        val type = environment.getProperty("embabel.agent.mcpserver.type", "SYNC")
        return enabled && type == "SYNC"
    }
}

/**
 * Sync server configuration using template method pattern.
 * Eliminates duplication while providing sync-specific implementations.
 */
@Configuration
@Conditional(McpSyncServerCondition::class)
class McpSyncServerConfiguration(
    applicationContext: ConfigurableApplicationContext
) : AbstractMcpServerConfiguration(applicationContext) {

    private val serverInfo = ServerInfoFactory.create(McpExecutionMode.SYNC)

    @Bean
    fun syncBannerCallback(): ToolCallbackProvider = createBannerTool()

    override fun createServerStrategy(): McpServerStrategy {
        val syncServer = applicationContext.getBean(McpSyncServer::class.java)
        return SyncServerStrategy(syncServer)
    }

    override fun createBannerTool(): ToolCallbackProvider {
        return MethodToolCallbackProvider.builder()
            .toolObjects(UnifiedBannerTool(serverInfo))
            .build()
    }

    override fun getToolPublishers(): List<McpToolExportCallbackPublisher> {
        return applicationContext.getBeansOfType(McpToolExportCallbackPublisher::class.java).values.toList()
    }

    override fun getResourcePublishers(): List<McpResourcePublisher> {
        return applicationContext.getBeansOfType(McpResourcePublisher::class.java).values.toList()
    }

    override fun getPromptPublishers(): List<McpPromptPublisher> {
        return applicationContext.getBeansOfType(McpPromptPublisher::class.java).values.toList()
    }

    override fun convertToToolSpecifications(toolCallbacks: List<Any>): List<Any> {
        // Cast to the expected ToolCallback type for McpToolUtils
        val callbacks = toolCallbacks.filterIsInstance<org.springframework.ai.tool.ToolCallback>()
        return McpToolUtils.toSyncToolSpecification(callbacks)
    }

    override fun getExecutionMode(): String = "SYNC"
}
