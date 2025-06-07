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
import com.embabel.agent.event.AgentPlatformEvent
import com.embabel.agent.event.logging.LoggingPersonality.Companion.BANNER_WIDTH
import com.embabel.agent.spi.support.AgentScanningBeanPostProcessorEvent
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.loggerFor
import io.modelcontextprotocol.server.McpSyncServer
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.ApplicationEvent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener

/**
 * Tag interface extending Spring AI ToolCallbackProvider
 * that identifies tool callbacks that our MCP server exposes.
 */
interface McpToolExportCallbackPublisher : ToolCallbackPublisher, HasInfoString

/**
 * Configures MCP server. Exposes a limited number of tools.
 */
@Configuration
@Profile("!test")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.ANY)
class McpServerConfiguration(
    private val applicationContext: ConfigurableApplicationContext,
) {


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

        (applicationContext.beanFactory as DefaultListableBeanFactory)
            .registerSingleton("callbacks", ToolCallbackProvider { allToolCallbacks.toTypedArray() })

        //TODO, add support for MCP Async Server, find out if needed.
        refreshBean("mcpSyncServer", McpSyncServer::class.java)
    }

    internal fun refreshBean(beanName: String, beanClass: Class<*>) {
        val beanFactory = applicationContext.beanFactory as? DefaultListableBeanFactory
            ?: throw IllegalStateException("BeanFactory is not a DefaultListableBeanFactory")

        try {
            // Get current bean definition
            if (!beanFactory.containsBeanDefinition(beanName)) {
                loggerFor<McpServerConfiguration>().error("Bean definition for '$beanName' not found")
                return
            }
            val beanDefinition = beanFactory.getBeanDefinition(beanName)

            // Destroy current instance
            if (beanFactory.containsSingleton(beanName)) {
                beanFactory.destroySingleton(beanName)
            }

            val instance = beanFactory.getBean(beanName, beanClass)

            loggerFor<McpServerConfiguration>().debug("Refreshing bean '$beanName' of type ${instance::class.java.name}")
        } catch (ex: Exception) {
            loggerFor<McpServerConfiguration>().error(
                "Failed to refresh bean '$beanName'. Agent Server Tools will not be available: ${ex.message}",
                ex
            )
        }
    }
}
