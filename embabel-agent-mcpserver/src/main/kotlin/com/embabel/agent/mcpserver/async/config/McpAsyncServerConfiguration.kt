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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.McpToolUtils
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.*
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Condition that checks if the MCP server is enabled and set to ASYNC mode.
 */
class McpAsyncServerCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata
    ): Boolean {
        val environment = context.environment
        val type = environment.getProperty("spring.ai.mcp.server.type", "SYNC")
        return type == "ASYNC"
    }
}

/**
 * Configuration for the asynchronous MCP server.
 * Uses the template method pattern to provide beans and server strategy for async mode.
 *
 * @param applicationContext the Spring application context
 */
@Configuration
@Conditional(McpAsyncServerCondition::class)
class McpAsyncServerConfiguration(
    applicationContext: ConfigurableApplicationContext,
) : AbstractMcpServerConfiguration(applicationContext) {

    override val logger: Logger = LoggerFactory.getLogger(McpAsyncServerConfiguration::class.java)

    private val serverInfo = ServerInfoFactory.create(McpExecutionMode.ASYNC)

    @Bean
    fun asyncBannerCallback(): ToolCallbackProvider = createBannerTool()

    /**
     * Creates the server strategy specific to async mode.
     * Retrieves the McpAsyncServer bean from the application context.
     *
     * @return the McpServerStrategy for async operations
     */
    override fun createServerStrategy(): McpServerStrategy {
        val asyncServer = applicationContext.getBean(McpAsyncServer::class.java)
        return AsyncServerStrategy(asyncServer)
    }

    /**
     * Creates a banner tool callback provider for the asynchronous MCP server.
     *
     * This method builds a `MethodToolCallbackProvider` using the `UnifiedBannerTool`
     * initialized with the current server info. The resulting provider is used to
     * expose banner-related tool callbacks in async server mode.
     *
     * @return a `ToolCallbackProvider` for the async server banner tool
     */
    override fun createBannerTool(): ToolCallbackProvider {
        return MethodToolCallbackProvider.builder()
            .toolObjects(UnifiedBannerTool(serverInfo))
            .build()
    }

    /**
     * Returns all tool callback publishers registered in the application context.
     *
     * This method collects beans of type `McpToolExportCallbackPublisher` and
     * returns them as a list for use in tool export operations.
     *
     * @return a list of `McpToolExportCallbackPublisher` instances
     */
    override fun getToolPublishers(): List<McpToolExportCallbackPublisher> {
        return applicationContext.getBeansOfType(McpToolExportCallbackPublisher::class.java).values.toList()
    }

    /**
     * Returns all async resource publishers registered in the application context.
     *
     * This method collects beans of type `McpAsyncResourcePublisher` and
     * returns them as a list for use in async resource export operations.
     *
     * @return a list of `McpAsyncResourcePublisher` instances
     */
    override fun getResourcePublishers(): List<McpAsyncResourcePublisher> {
        return applicationContext.getBeansOfType(McpAsyncResourcePublisher::class.java).values.toList()
    }

    /**
     * Returns all async prompt publishers registered in the application context.
     *
     * This method collects beans of type `McpAsyncPromptPublisher` and
     * returns them as a list for use in async prompt export operations.
     *
     * @return a list of `McpAsyncPromptPublisher` instances
     */
    override fun getPromptPublishers(): List<McpAsyncPromptPublisher> {
        return applicationContext.getBeansOfType(McpAsyncPromptPublisher::class.java).values.toList()
    }


    /**
     * Converts a list of tool callback objects to async tool specifications.
     *
     * Filters the provided list for instances of `ToolCallback` and transforms them
     * into async tool specifications using `McpToolUtils`.
     *
     * @param toolCallbacks a list of tool callback objects
     * @return a list of async tool specifications
     */
    override fun convertToToolSpecifications(toolCallbacks: List<Any>): List<Any> {
        val callbacks = toolCallbacks.filterIsInstance<org.springframework.ai.tool.ToolCallback>()
        return McpToolUtils.toAsyncToolSpecifications(callbacks)
    }

    /**
     * Returns the execution mode for this server configuration.
     *
     * @return the string `"ASYNC"` representing async execution mode
     */
    override fun getExecutionMode(): String = "ASYNC"
}
