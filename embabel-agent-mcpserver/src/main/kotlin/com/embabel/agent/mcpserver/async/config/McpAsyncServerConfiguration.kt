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
package com.embabel.agent.mcpserver.async.config

import com.embabel.agent.mcpserver.*
import com.embabel.agent.mcpserver.async.AsyncServerStrategy
import com.embabel.agent.mcpserver.async.McpAsyncPromptPublisher
import com.embabel.agent.mcpserver.async.McpAsyncResourcePublisher
import com.embabel.agent.mcpserver.domain.McpExecutionMode
import io.modelcontextprotocol.server.McpAsyncServer
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration

/**
 *
 */
class McpAsyncServerCondition : org.springframework.context.annotation.Condition {
    override fun matches(
        context: org.springframework.context.annotation.ConditionContext,
        metadata: org.springframework.core.type.AnnotatedTypeMetadata
    ): Boolean {
        val environment = context.environment
        val enabled = environment.getProperty("embabel.agent.mcpserver.enabled", Boolean::class.java, false)
        val type = environment.getProperty("embabel.agent.mcpserver.type", "SYNC")
        return enabled && type == "ASYNC"
    }
}

/**
 * Async server configuration using template method pattern.
 */
@Configuration
@Conditional(McpAsyncServerCondition::class)
class McpAsyncServerConfiguration(
    applicationContext: ConfigurableApplicationContext
) : AbstractMcpServerConfiguration(applicationContext) {

    private val serverInfo = ServerInfoFactory.create(McpExecutionMode.ASYNC)

    @Bean
    fun asyncBannerCallback(): ToolCallbackProvider = createBannerTool()

    override fun createServerStrategy(): McpServerStrategy {
        val asyncServer = applicationContext.getBean(McpAsyncServer::class.java)
        return AsyncServerStrategy(asyncServer)
    }

    override fun createBannerTool(): ToolCallbackProvider {
        return MethodToolCallbackProvider.builder()
            .toolObjects(UnifiedBannerTool(serverInfo))
            .build()
    }

    override fun getToolPublishers(): List<McpToolExportCallbackPublisher> {
        return applicationContext.getBeansOfType(McpToolExportCallbackPublisher::class.java).values.toList()
    }

    override fun getResourcePublishers(): List<McpAsyncResourcePublisher> {
        return applicationContext.getBeansOfType(McpAsyncResourcePublisher::class.java).values.toList()
    }

    override fun getPromptPublishers(): List<McpAsyncPromptPublisher> {
        return applicationContext.getBeansOfType(McpAsyncPromptPublisher::class.java).values.toList()
    }

    override fun convertToToolSpecifications(toolCallbacks: List<Any>): List<Any> {
        val callbacks = toolCallbacks.filterIsInstance<org.springframework.ai.tool.ToolCallback>()
        return McpToolUtils.toAsyncToolSpecifications(callbacks)
    }

    override fun getExecutionMode(): String = "ASYNC"
}
