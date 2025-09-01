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
package com.embabel.agent.starter.mcpserver.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Environment post-processor that enables MCP server functionality early in the Spring Boot lifecycle.
 *
 * <p>The processor runs early in the Spring Boot lifecycle to ensure MCP server enablement occurs
 * before configuration classes are processed, guaranteeing proper component initialization.
 */
public class McpServerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(McpServerEnvironmentPostProcessor.class);

    // Property source configuration
    private static final String MCP_SERVER_PROPERTIES_SOURCE_NAME = "mcpServerModeProperties";
    private static final String MCP_SERVER_ENABLED_PROPERTY = "embabel.agent.mcpserver.enabled";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // Run early, but after core Spring Boot processors
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        logger.debug("MCP server mode detected - applying MCP server environment configuration");

        applyMcpServerConfiguration(environment);

        logger.debug("MCP server environment configuration applied successfully");
    }

    /**
     * Applies the MCP server configuration to the environment by adding a high-priority property source.
     *
     * @param environment the configurable environment
     */
    private void applyMcpServerConfiguration(ConfigurableEnvironment environment) {
        Map<String, Object> mcpServerProperties = Map.of(
                MCP_SERVER_ENABLED_PROPERTY, true
        );

        environment.getPropertySources().addFirst(
                new MapPropertySource(MCP_SERVER_PROPERTIES_SOURCE_NAME, mcpServerProperties)
        );

        logger.debug("Added MCP server properties with {} entries", mcpServerProperties.size());
    }
}
