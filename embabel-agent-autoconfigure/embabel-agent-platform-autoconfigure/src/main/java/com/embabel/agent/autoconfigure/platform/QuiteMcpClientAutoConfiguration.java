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
package com.embabel.agent.autoconfigure.platform;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.spring.ClientMcpAsyncHandlersRegistry;
import org.springframework.ai.mcp.annotation.spring.ClientMcpSyncHandlersRegistry;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.configurer.McpAsyncClientConfigurer;
import org.springframework.ai.mcp.client.common.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.customizer.McpAsyncClientCustomizer;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced auto-configuration for Model Context Protocol (MCP) client support.
 *
 * <p>
 * This configuration extends Spring AI's {@link McpClientAutoConfiguration} with additional
 * resilience handling to prevent runtime exceptions during MCP client initialization from
 * disrupting the Agent Platform startup. Failed client initializations are logged but do not
 * prevent the application from starting successfully.
 *
 * <p>
 * Key differences from base Spring AI configuration:
 * <ul>
 * <li>Exception Resilience: Wraps client initialization in try-catch blocks to handle failures gracefully
 * <li>Startup Protection: Prevents MCP client failures from blocking Agent Platform startup
 * <li>Error Logging: Failed initializations are logged with client names for troubleshooting
 * <li>Partial Success: Successfully initialized clients are added to the context even if others fail
 * </ul>
 *
 * <p>
 * Configuration Properties:
 * <ul>
 * <li>{@code spring.ai.mcp.client.enabled} - Enable/disable MCP client support (default: true)
 * <li>{@code spring.ai.mcp.client.type} - Client type: SYNC or ASYNC (default: SYNC)
 * <li>{@code spring.ai.mcp.client.name} - Client implementation name
 * <li>{@code spring.ai.mcp.client.version} - Client implementation version
 * <li>{@code spring.ai.mcp.client.request-timeout} - Request timeout duration
 * <li>{@code spring.ai.mcp.client.initialized} - Whether to initialize clients on creation
 * </ul>
 *
 * <p>
 * The configuration is activated after the transport-specific auto-configurations (Stdio,
 * SSE HTTP, and SSE WebFlux) to ensure proper initialization order. At least one
 * transport must be available for the clients to be created.
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Synchronous and Asynchronous Client Support:
 * <ul>
 * <li>Creates and configures MCP clients based on available transports
 * <li>Supports both blocking (sync) and non-blocking (async) operations
 * <li>Automatic client initialization with failure resilience
 * </ul>
 * <li>Integration Support:
 * <ul>
 * <li>Sets up tool callbacks for Spring AI integration
 * <li>Supports multiple named transports
 * <li>Proper lifecycle management with automatic cleanup
 * </ul>
 * <li>Customization Options:
 * <ul>
 * <li>Extensible through {@link McpSyncClientCustomizer} and {@link McpAsyncClientCustomizer}
 * <li>Configurable timeouts and client information
 * <li>Support for custom transport implementations
 * </ul>
 * </ul>
 *
 * @see McpClientAutoConfiguration
 * @see McpSyncClient
 * @see McpAsyncClient
 * @see McpClientCommonProperties
 * @see McpSyncClientCustomizer
 * @see McpAsyncClientCustomizer
 * @see StdioTransportAutoConfiguration
 */
@AutoConfiguration(afterName = {
        "org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration",
        "org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration",
        "org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration",
        "org.springframework.ai.mcp.client.webflux.autoconfigure.SseWebFluxTransportAutoConfiguration",
        "org.springframework.ai.mcp.client.webflux.autoconfigure.StreamableHttpWebFluxTransportAutoConfiguration"})
@ConditionalOnClass(McpSchema.class)
@EnableConfigurationProperties(McpClientCommonProperties.class)
@ConditionalOnProperty(prefix = McpClientCommonProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class QuiteMcpClientAutoConfiguration extends McpClientAutoConfiguration {

    final private static Logger logger = LoggerFactory.getLogger(QuiteMcpClientAutoConfiguration.class);

    private String connectedClientName(String clientName, String serverConnectionName) {
        return clientName + " - " + serverConnectionName;
    }

    /**
     * Creates a list of {@link McpSyncClient} instances based on the available transports.
     *
     * <p>
     * This method overrides the base Spring AI implementation to add exception handling
     * during client initialization. Clients that fail to initialize are logged and excluded
     * from the returned list, allowing the application to start with partially available
     * MCP functionality rather than failing completely.
     *
     * <p>
     * Each successfully configured client includes:
     * <ul>
     * <li>Client information (name and version) from common properties
     * <li>Request timeout settings
     * <li>Custom configurations through {@link McpSyncClientConfigurer}
     * <li>Handler registrations for sampling, elicitation, logging, progress, and resource changes
     * </ul>
     *
     * <p>
     * If initialization is enabled in properties, the clients are automatically
     * initialized with error resilience.
     *
     * @param mcpSyncClientConfigurer       the configurer for customizing client creation
     * @param commonProperties              common MCP client properties
     * @param transportsProvider            provider of named MCP transports
     * @param clientMcpSyncHandlersRegistry registry for client-side synchronous handlers
     * @return list of successfully initialized MCP sync clients (may be empty if all fail)
     */
    @Bean
    @ConditionalOnProperty(prefix = McpClientCommonProperties.CONFIG_PREFIX, name = "type", havingValue = "SYNC",
            matchIfMissing = true)
    public List<McpSyncClient> mcpSyncClients(McpSyncClientConfigurer mcpSyncClientConfigurer,
                                              McpClientCommonProperties commonProperties,
                                              ObjectProvider<List<NamedClientMcpTransport>> transportsProvider,
                                              ObjectProvider<ClientMcpSyncHandlersRegistry> clientMcpSyncHandlersRegistry) {

        List<McpSyncClient> mcpSyncClients = new ArrayList<>();

        List<NamedClientMcpTransport> namedTransports = transportsProvider.stream().flatMap(List::stream).toList();

        if (!CollectionUtils.isEmpty(namedTransports)) {
            for (NamedClientMcpTransport namedTransport : namedTransports) {

                McpSchema.Implementation clientInfo = new McpSchema.Implementation(
                        this.connectedClientName(commonProperties.getName(), namedTransport.name()),
                        namedTransport.name(), commonProperties.getVersion());

                McpClient.SyncSpec spec = McpClient.sync(namedTransport.transport())
                        .clientInfo(clientInfo)
                        .requestTimeout(commonProperties.getRequestTimeout());

                clientMcpSyncHandlersRegistry.ifAvailable(registry -> spec
                        .sampling(samplingRequest -> registry.handleSampling(namedTransport.name(), samplingRequest))
                        .elicitation(
                                elicitationRequest -> registry.handleElicitation(namedTransport.name(), elicitationRequest))
                        .loggingConsumer(loggingMessageNotification -> registry.handleLogging(namedTransport.name(),
                                loggingMessageNotification))
                        .progressConsumer(progressNotification -> registry.handleProgress(namedTransport.name(),
                                progressNotification))
                        .toolsChangeConsumer(newTools -> registry.handleToolListChanged(namedTransport.name(), newTools))
                        .promptsChangeConsumer(
                                newPrompts -> registry.handlePromptListChanged(namedTransport.name(), newPrompts))
                        .resourcesChangeConsumer(
                                newResources -> registry.handleResourceListChanged(namedTransport.name(), newResources))
                        .capabilities(registry.getCapabilities(namedTransport.name())));

                McpClient.SyncSpec customizedSpec = mcpSyncClientConfigurer.configure(namedTransport.name(), spec);

                var client = customizedSpec.build();

                if (commonProperties.isInitialized()) {
                    try {
                        client.initialize();
                        mcpSyncClients.add(client);
                    } catch (Throwable t) {
                        logger.error("Failed to initialize MCP Sync Client: {} - Application startup will continue",
                                clientInfo.name(), t);
                    }
                } else {
                    mcpSyncClients.add(client);
                }
            }
        }

        return mcpSyncClients;
    }

    /**
     * Creates a list of {@link McpAsyncClient} instances based on the available transports.
     *
     * <p>
     * This method overrides the base Spring AI implementation to add exception handling
     * during client initialization. Clients that fail to initialize are logged and excluded
     * from the returned list, allowing the application to start with partially available
     * MCP functionality rather than failing completely.
     *
     * <p>
     * Each successfully configured client includes:
     * <ul>
     * <li>Client information (name and version) from common properties
     * <li>Request timeout settings
     * <li>Custom configurations through {@link McpAsyncClientConfigurer}
     * <li>Handler registrations for sampling, elicitation, logging, progress, and resource changes
     * </ul>
     *
     * <p>
     * If initialization is enabled in properties, the clients are automatically
     * initialized with error resilience using reactive blocking.
     *
     * @param mcpAsyncClientConfigurer       the configurer for customizing client creation
     * @param commonProperties               common MCP client properties
     * @param transportsProvider             provider of named MCP transports
     * @param clientMcpAsyncHandlersRegistry registry for client-side asynchronous handlers
     * @return list of successfully initialized MCP async clients (may be empty if all fail)
     */
    @Bean
    @ConditionalOnProperty(prefix = McpClientCommonProperties.CONFIG_PREFIX, name = "type", havingValue = "ASYNC")
    public List<McpAsyncClient> mcpAsyncClients(McpAsyncClientConfigurer mcpAsyncClientConfigurer,
                                                McpClientCommonProperties commonProperties,
                                                ObjectProvider<List<NamedClientMcpTransport>> transportsProvider,
                                                ObjectProvider<ClientMcpAsyncHandlersRegistry> clientMcpAsyncHandlersRegistry) {

        List<McpAsyncClient> mcpAsyncClients = new ArrayList<>();

        List<NamedClientMcpTransport> namedTransports = transportsProvider.stream().flatMap(List::stream).toList();

        if (!CollectionUtils.isEmpty(namedTransports)) {
            for (NamedClientMcpTransport namedTransport : namedTransports) {

                McpSchema.Implementation clientInfo = new McpSchema.Implementation(
                        this.connectedClientName(commonProperties.getName(), namedTransport.name()),
                        commonProperties.getVersion());
                McpClient.AsyncSpec spec = McpClient.async(namedTransport.transport())
                        .clientInfo(clientInfo)
                        .requestTimeout(commonProperties.getRequestTimeout());
                clientMcpAsyncHandlersRegistry.ifAvailable(registry -> spec
                        .sampling(samplingRequest -> registry.handleSampling(namedTransport.name(), samplingRequest))
                        .elicitation(
                                elicitationRequest -> registry.handleElicitation(namedTransport.name(), elicitationRequest))
                        .loggingConsumer(loggingMessageNotification -> registry.handleLogging(namedTransport.name(),
                                loggingMessageNotification))
                        .progressConsumer(progressNotification -> registry.handleProgress(namedTransport.name(),
                                progressNotification))
                        .toolsChangeConsumer(newTools -> registry.handleToolListChanged(namedTransport.name(), newTools))
                        .promptsChangeConsumer(
                                newPrompts -> registry.handlePromptListChanged(namedTransport.name(), newPrompts))
                        .resourcesChangeConsumer(
                                newResources -> registry.handleResourceListChanged(namedTransport.name(), newResources))
                        .capabilities(registry.getCapabilities(namedTransport.name())));

                McpClient.AsyncSpec customizedSpec = mcpAsyncClientConfigurer.configure(namedTransport.name(), spec);

                var client = customizedSpec.build();

                if (commonProperties.isInitialized()) {
                    try {
                        client.initialize().block();
                        mcpAsyncClients.add(client);
                    } catch (Throwable t) {
                        logger.error("Failed to initialize MCP Async Client: {} - Application startup will continue",
                                clientInfo.name(), t);
                    }
                } else {
                    mcpAsyncClients.add(client);
                }
            }
        }

        return mcpAsyncClients;
    }
}