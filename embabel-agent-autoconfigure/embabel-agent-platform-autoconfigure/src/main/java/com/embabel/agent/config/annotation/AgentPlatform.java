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
package com.embabel.agent.config.annotation;

import com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration;
import com.embabel.agent.config.annotation.spi.EnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Core meta-annotation that enables the Embabel Agent platform infrastructure.
 *
 * <p>This annotation serves as the foundation for all agent-specific annotations
 * in the Embabel framework. It bootstraps the agent platform by importing necessary
 * auto-configuration, scanning for components, and activating platform-specific
 * Spring profiles.
 *
 * <h3>Primary Purpose:</h3>
 * <p>This is a <b>meta-annotation</b> designed to be used on other annotations rather
 * than directly on application classes. It provides the common configuration needed
 * by all agent platform variants (Shell, MCP Server, Bedrock, etc.).
 *
 * <h3>What This Annotation Does:</h3>
 * <ul>
 *   <li><b>Imports Auto-Configuration</b>: Loads {@link AgentPlatformAutoConfiguration}
 *       which sets up core agent infrastructure</li>
 *   <li><b>Scans Configuration Properties</b>: Discovers all {@code @ConfigurationProperties}
 *       classes in the {@code com.embabel.agent} package</li>
 *   <li><b>Component Scanning</b>: Registers all Spring components in the
 *       {@code com.embabel.agent.autoconfigure} package</li>
 *   <li><b>Profile Activation</b>: Activates Spring profiles based on the {@code value}
 *       attribute (e.g., "shell", "mcp-server", "bedrock")</li>
 * </ul>
 *
 * <h3>Usage as Meta-Annotation:</h3>
 * <pre>{@code
 * // Creating a custom agent annotation
 * @Retention(RetentionPolicy.RUNTIME)
 * @Target(ElementType.TYPE)
 * @AgentPlatform("my-platform")
 * public @interface EnableMyAgentPlatform {
 * }
 * }</pre>
 *
 * <h3>Direct Usage (Rare):</h3>
 * <pre>{@code
 * // Only for advanced customization
 * @SpringBootApplication
 * @AgentPlatform({"custom-profile-1", "custom-profile-2"})
 * public class CustomAgentApplication {
 * }
 * }</pre>
 *
 * <h3>Platform Profiles:</h3>
 * <p>The {@code value} attribute specifies which Spring profiles to activate:
 * <ul>
 *   <li>{@code "default"} - Basic agent platform without specific features</li>
 *   <li>{@code "shell"} - Interactive command-line interface mode</li>
 *   <li>{@code "mcp-server"} - Model Context Protocol server mode</li>
 *   <li>{@code "bedrock"} - AWS Bedrock integration mode</li>
 *   <li>{@code "a2a-server"} - Agent-to-Agent communication server</li>
 * </ul>
 *
 * <h3>Component Discovery:</h3>
 * <p>This annotation ensures the following are discovered and registered:
 * <ul>
 *   <li>All {@code @Agent} annotated classes</li>
 *   <li>Custom {@code @Tool} implementations</li>
 *   <li>Agent {@code @Repository} interfaces</li>
 *   <li>Configuration properties for agent behavior</li>
 *   <li>Auto-configuration classes for platform features</li>
 * </ul>
 *
 * <h3>Relationship to Other Annotations:</h3>
 * <p>This annotation is used as a building block by:
 * <ul>
 *   <li>{@link EnableAgentShell} - Adds {@code @AgentPlatform("shell")}</li>
 *   <li>{@link EnableAgentMcpServer} - Adds {@code @AgentPlatform("mcp-server")}</li>
 *   <li>{@link EnableAgentBedrock} - Adds {@code @AgentPlatform("shell, bedrock")}</li>
 *   <li>{@link EnableAgents} - Adds {@code @AgentPlatform} with default value</li>
 * </ul>
 *
 * <h3>Advanced Configuration:</h3>
 * <p>When multiple {@code @AgentPlatform} annotations are present (through meta-annotations),
 * their profiles are merged. For example:
 * <pre>{@code
 * @EnableAgentShell        // Contributes "shell"
 * @EnableAgentBedrock      // Contributes "shell, bedrock"
 * // Result: Profiles "shell" and "bedrock" are both active
 * }</pre>
 *
 * <h3>Implementation Note:</h3>
 * <p>The actual profile activation is handled by {@link EnvironmentPostProcessor}
 * which processes this annotation during the Spring Boot startup sequence.
 *
 * @see EnableAgentShell
 * @see EnableAgentMcpServer
 * @see EnableAgentBedrock
 * @see EnableAgents
 * @see AgentPlatformAutoConfiguration
 * @since 1.0
 * @author Embabel Team
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent"
        }
)
@ComponentScan(
        basePackages = {
                "com.embabel.agent.autoconfigure"
        }
)
@ImportAutoConfiguration(classes = {AgentPlatformAutoConfiguration.class})
public @interface AgentPlatform {
    /**
     * Specifies the platform profiles to activate.
     *
     * <p>Each value in this array will be registered as an active Spring profile,
     * enabling platform-specific configurations and beans.
     *
     * <h4>Common Values:</h4>
     * <ul>
     *   <li>{@code "shell"} - Interactive CLI mode</li>
     *   <li>{@code "mcp-server"} - MCP protocol server</li>
     *   <li>{@code "bedrock"} - AWS Bedrock integration</li>
     * </ul>
     *
     * <h4>Multiple Profiles:</h4>
     * <p>Multiple profiles can be specified to combine features:
     * <pre>{@code
     * @AgentPlatform({"shell", "metrics", "observability"})
     * }</pre>
     *
     * @return array of profile names to activate
     */
    String[] value() default {};
}
