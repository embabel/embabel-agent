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

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/**
 * Auto-configuration filter that excludes Spring AI's default MCP client auto-configuration
 * to allow the Agent Platform's resilient implementation to take precedence.
 *
 * <p>
 * This filter implements Spring Boot's {@link AutoConfigurationImportFilter} interface to
 * intercept and filter auto-configuration classes during application context initialization.
 * It specifically excludes {@code McpClientAutoConfiguration} from Spring AI, enabling the
 * Agent Platform to register its own {@code QuiteMcpClientAutoConfiguration} which extends
 * the base configuration with enhanced error handling and startup resilience.
 *
 * <h2>Purpose</h2>
 * Spring AI's default {@code McpClientAutoConfiguration} can cause application startup failures
 * when MCP client initialization encounters errors. This filter prevents that auto-configuration
 * from loading, allowing {@code QuiteMcpClientAutoConfiguration} to provide the same functionality
 * with graceful error handling that logs failures without blocking application startup.
 *
 * <h2>How It Works</h2>
 * <ol>
 * <li>Spring Boot discovers all auto-configurations from {@code META-INF/spring.factories}
 * or {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}</li>
 * <li>Before loading them, it applies registered {@link AutoConfigurationImportFilter} instances</li>
 * <li>This filter examines each auto-configuration class name</li>
 * <li>Returns {@code false} for {@code McpClientAutoConfiguration}, preventing its registration</li>
 * <li>Returns {@code true} for all other auto-configurations, allowing normal processing</li>
 * </ol>
 *
 * <h2>Registration</h2>
 * This filter must be registered in {@code META-INF/spring.factories}:
 * <pre>
 * org.springframework.boot.autoconfigure.AutoConfigurationImportFilter=\
 * com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfigurationFilter
 * </pre>
 *
 * <h2>Relationship to QuiteMcpClientAutoConfiguration</h2>
 * This filter works in tandem with {@code QuiteMcpClientAutoConfiguration}:
 * <ul>
 * <li>Filter: Prevents Spring AI's default configuration from loading</li>
 * <li>QuiteMcpClientAutoConfiguration: Extends the excluded configuration with error handling</li>
 * <li>Result: Agent Platform gets resilient MCP client setup without startup failures</li>
 * </ul>
 *
 * <h2>Design Pattern Analogy</h2>
 * Think of this as a <strong>Strategic Design pattern from Domain-Driven Design</strong>:
 * <ul>
 * <li><strong>Spring AI's McpClientAutoConfiguration</strong> = External bounded context with its own rules</li>
 * <li><strong>This Filter</strong> = Context boundary enforcement preventing unwanted context intrusion</li>
 * <li><strong>QuiteMcpClientAutoConfiguration</strong> = Anti-Corruption Layer translating external
 * concepts into Agent Platform's resilient domain model</li>
 * </ul>
 *
 * <p>
 * Just as an Anti-Corruption Layer protects your domain from external system changes, this filter
 * protects the Agent Platform from Spring AI's initialization behavior while still leveraging
 * its core functionality through inheritance.
 *
 * <h2>Null Safety</h2>
 * The {@link #match(String[], AutoConfigurationMetadata)} implementation handles {@code null}
 * entries in the auto-configuration class array, which can occur during Spring Boot's internal
 * processing. Null entries are treated as non-matching (filtered out).
 *
 * @see QuiteMcpClientAutoConfiguration
 * @see AutoConfigurationImportFilter
 * @see org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration
 * @since 1.0
 */
public class AgentPlatformAutoConfigurationFilter implements AutoConfigurationImportFilter {

    /**
     * Fully qualified class name of Spring AI's MCP client auto-configuration to exclude.
     */
    private static final String MCP_CLIENT_AUTO_CONFIGURATION =
            "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration";

    /**
     * Filters auto-configuration classes to exclude Spring AI's default MCP client configuration.
     *
     * <p>
     * This method is called by Spring Boot during application context initialization to determine
     * which auto-configurations should be loaded. It evaluates each auto-configuration class name
     * and returns a boolean array indicating whether each should be included.
     *
     * <p>
     * <strong>Filtering Logic:</strong>
     * <ul>
     * <li>{@code null} entries → {@code false} (excluded for safety)</li>
     * <li>{@code McpClientAutoConfiguration} → {@code false} (explicitly excluded)</li>
     * <li>All other classes → {@code true} (allowed to load)</li>
     * </ul>
     *
     * @param autoConfigurationClasses array of auto-configuration class names to evaluate;
     *                                may contain {@code null} entries
     * @param metadata metadata about the auto-configurations (not used in this implementation)
     * @return boolean array where {@code true} means "include this auto-configuration" and
     *         {@code false} means "exclude this auto-configuration"; array length matches
     *         input array length
     */
    @Override
    public boolean[] match(String[] autoConfigurationClasses,
                           AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[autoConfigurationClasses.length];

        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            // Handle null entries and exclude McpClientAutoConfiguration
            if (autoConfigurationClasses[i] == null) {
                matches[i] = false;
            } else {
                matches[i] = !autoConfigurationClasses[i].equals(MCP_CLIENT_AUTO_CONFIGURATION);
            }
        }

        return matches;
    }
}