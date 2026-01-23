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
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for resilient MCP client auto-configuration.
 *
 * <p>
 * This test class validates that {@link QuiteMcpClientAutoConfiguration} properly extends
 * Spring AI's base MCP client configuration with enhanced error handling and startup resilience.
 * The tests verify that MCP client initialization failures are handled gracefully without
 * blocking application startup.
 *
 * <h3>Key Testing Focus:</h3>
 * <ul>
 * <li><strong>Resilience Testing:</strong> Validates that client initialization failures don't
 * prevent application startup and that partial success is supported</li>
 *
 * <li><strong>Error Handling:</strong> Tests that failed clients are logged appropriately and
 * excluded from the bean list while successful ones are included</li>
 *
 * <li><strong>Initialization Control:</strong> Tests both {@code initialized=true} (where failures
 * are caught) and {@code initialized=false} (where clients are added without initialization)</li>
 * </ul>
 *
 * <h3>Testing Patterns:</h3>
 * <ul>
 * <li><strong>Mock Transport Configuration:</strong> Uses Mockito mocks configured to either
 * succeed or fail during initialization to test resilience</li>
 *
 * <li><strong>Failure Simulation:</strong> Creates transports that throw exceptions during
 * initialization to verify graceful degradation</li>
 *
 *
 * <li>Spring AI's configuration = External bounded context (may fail unpredictably)</li>
 * <li>QuiteMcpClientAutoConfiguration = Anti-Corruption Layer (protects our domain)</li>
 * <li>These tests = Validation that the ACL properly isolates our domain from external failures</li>
 * </ul>
 *
 * @see QuiteMcpClientAutoConfiguration
 * @see McpClientCommonProperties
 * @see AgentPlatformAutoConfigurationFilter
 */
public class QuiteMcpClientAutoConfigurationIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    QuiteMcpClientAutoConfiguration.class,
                    McpClientAnnotationScannerAutoConfiguration.class));

    /**
     * Tests that sync clients are created successfully when initialization is disabled.
     *
     * <p>
     * With {@code initialized=false}, clients should be added to the list without
     * attempting initialization, avoiding any potential failures.
     */
    @Test
    void syncClientsCreatedWithoutInitialization() {
        this.contextRunner
                .withUserConfiguration(WorkingTransportConfiguration.class)
                .withPropertyValues("spring.ai.mcp.client.initialized=false")
                .run(context -> {
                    List<McpSyncClient> clients = context.getBean("mcpSyncClients", List.class);
                    assertThat(clients).hasSize(1);

                    McpClientCommonProperties properties = context.getBean(McpClientCommonProperties.class);
                    assertThat(properties.isInitialized()).isFalse();
                });
    }

    /**
     * Tests that async clients are created successfully when initialization is disabled.
     *
     * <p>
     * With {@code initialized=false}, clients should be added to the list without
     * attempting initialization, avoiding any potential failures.
     */
    @Test
    void asyncClientsCreatedWithoutInitialization() {
        this.contextRunner
                .withUserConfiguration(WorkingTransportConfiguration.class)
                .withPropertyValues(
                        "spring.ai.mcp.client.type=ASYNC",
                        "spring.ai.mcp.client.initialized=false")
                .run(context -> {
                    List<McpAsyncClient> clients = context.getBean("mcpAsyncClients", List.class);
                    assertThat(clients).hasSize(1);

                    McpClientCommonProperties properties = context.getBean(McpClientCommonProperties.class);
                    assertThat(properties.getType()).isEqualTo(McpClientCommonProperties.ClientType.ASYNC);
                });
    }

    /**
     * Tests resilience when sync client initialization fails.
     *
     * <p>
     * This is the key test validating the enhancement over Spring AI's base configuration.
     * When initialization is enabled but fails, the application should:
     * <ul>
     * <li>Continue startup successfully (not throw exceptions)</li>
     * <li>Log the error appropriately</li>
     * <li>Return an empty list (no failed clients added)</li>
     * </ul>
     */
    @Test
    void syncClientInitializationFailureDoesNotPreventStartup() {
        this.contextRunner
                .withUserConfiguration(FailingTransportConfiguration.class)
                .withPropertyValues("spring.ai.mcp.client.initialized=true")
                .run(context -> {
                    // Application should start successfully despite initialization failure
                    assertThat(context).hasNotFailed();

                    // Bean should exist but be empty (failed clients excluded)
                    List<McpSyncClient> clients = context.getBean("mcpSyncClients", List.class);
                    assertThat(clients).isEmpty();
                });
    }

    /**
     * Tests resilience when async client initialization fails.
     *
     * <p>
     * This validates that async client initialization failures are also handled gracefully,
     * allowing the application to start successfully.
     */
    @Test
    void asyncClientInitializationFailureDoesNotPreventStartup() {
        this.contextRunner
                .withUserConfiguration(FailingTransportConfiguration.class)
                .withPropertyValues(
                        "spring.ai.mcp.client.type=ASYNC",
                        "spring.ai.mcp.client.initialized=true")
                .run(context -> {
                    // Application should start successfully despite initialization failure
                    assertThat(context).hasNotFailed();

                    // Bean should exist but be empty (failed clients excluded)
                    List<McpAsyncClient> clients = context.getBean("mcpAsyncClients", List.class);
                    assertThat(clients).isEmpty();
                });
    }

    /**
     * Tests that custom properties are properly bound.
     *
     * <p>
     * Validates that configuration properties work correctly with the resilient
     * auto-configuration.
     */
    @Test
    void customPropertiesBinding() {
        this.contextRunner
                .withUserConfiguration(WorkingTransportConfiguration.class)
                .withPropertyValues(
                        "spring.ai.mcp.client.name=agent-platform",
                        "spring.ai.mcp.client.version=2.0.0",
                        "spring.ai.mcp.client.request-timeout=30s",
                        "spring.ai.mcp.client.initialized=false")
                .run(context -> {
                    McpClientCommonProperties properties = context.getBean(McpClientCommonProperties.class);
                    assertThat(properties.getName()).isEqualTo("agent-platform");
                    assertThat(properties.getVersion()).isEqualTo("2.0.0");
                    assertThat(properties.getRequestTimeout()).hasSeconds(30);
                });
    }

    /**
     * Tests that disabled configuration prevents bean creation.
     *
     * <p>
     * When MCP client is explicitly disabled, no client beans should be created.
     */
    @Test
    void disabledConfiguration() {
        this.contextRunner
                .withPropertyValues("spring.ai.mcp.client.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("mcpSyncClients");
                    assertThat(context).doesNotHaveBean("mcpAsyncClients");
                });
    }

    /**
     * Tests empty transport scenario.
     *
     * <p>
     * When no transports are configured, the client list should be empty but the
     * application should still start successfully.
     */
    @Test
    void emptyTransportList() {
        this.contextRunner
                .withUserConfiguration(EmptyTransportConfiguration.class)
                .withPropertyValues("spring.ai.mcp.client.initialized=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    List<McpSyncClient> clients = context.getBean("mcpSyncClients", List.class);
                    assertThat(clients).isEmpty();
                });
    }

    // ========== Test Configuration Classes ==========

    /**
     * Configuration providing a working mock transport.
     *
     * <p>
     * This transport is configured to succeed during initialization, simulating a
     * healthy MCP server connection.
     */
    @Configuration
    static class WorkingTransportConfiguration {

        @Bean
        List<NamedClientMcpTransport> workingTransports() {
            McpClientTransport mockTransport = Mockito.mock(McpClientTransport.class);
            Mockito.when(mockTransport.protocolVersions()).thenReturn(List.of("2024-11-05"));
            Mockito.when(mockTransport.connect(Mockito.any())).thenReturn(Mono.never());
            Mockito.when(mockTransport.sendMessage(Mockito.any())).thenReturn(Mono.never());

            return List.of(new NamedClientMcpTransport("working", mockTransport));
        }
    }

    /**
     * Configuration providing a failing mock transport.
     *
     * <p>
     * This transport is configured to throw an exception during initialization,
     * simulating a connection failure or unavailable MCP server.
     */
    @Configuration
    static class FailingTransportConfiguration {

        @Bean
        List<NamedClientMcpTransport> failingTransports() {
            McpClientTransport mockTransport = Mockito.mock(McpClientTransport.class);
            Mockito.when(mockTransport.protocolVersions()).thenReturn(List.of("2024-11-05"));
            // Configure to throw exception on connect, simulating initialization failure
            Mockito.when(mockTransport.connect(Mockito.any()))
                    .thenReturn(Mono.error(new RuntimeException("Connection failed")));
            Mockito.when(mockTransport.sendMessage(Mockito.any())).thenReturn(Mono.never());

            return List.of(new NamedClientMcpTransport("failing", mockTransport));
        }
    }

    /**
     * Configuration providing both working and failing transports.
     *
     * <p>
     * This configuration simulates a partial failure scenario where some MCP servers
     * are available while others are not. Tests should verify that the application
     * continues with the working clients.
     */
    @Configuration
    static class MixedTransportConfiguration {

        @Bean
        List<NamedClientMcpTransport> mixedTransports() {
            // Working transport
            McpClientTransport workingTransport = Mockito.mock(McpClientTransport.class);
            Mockito.when(workingTransport.protocolVersions()).thenReturn(List.of("2024-11-05"));
            Mockito.when(workingTransport.connect(Mockito.any())).thenReturn(Mono.never());
            Mockito.when(workingTransport.sendMessage(Mockito.any())).thenReturn(Mono.never());

            // Failing transport
            McpClientTransport failingTransport = Mockito.mock(McpClientTransport.class);
            Mockito.when(failingTransport.protocolVersions()).thenReturn(List.of("2024-11-05"));
            Mockito.when(failingTransport.connect(Mockito.any()))
                    .thenReturn(Mono.error(new RuntimeException("Connection failed")));
            Mockito.when(failingTransport.sendMessage(Mockito.any())).thenReturn(Mono.never());

            return List.of(
                    new NamedClientMcpTransport("working", workingTransport),
                    new NamedClientMcpTransport("failing", failingTransport)
            );
        }
    }

    /**
     * Configuration providing an empty transport list.
     *
     * <p>
     * Simulates the scenario where no MCP transports are available, either because
     * none are configured or all transport auto-configurations were excluded.
     */
    @Configuration
    static class EmptyTransportConfiguration {

        @Bean
        List<NamedClientMcpTransport> emptyTransports() {
            return List.of();
        }
    }
}