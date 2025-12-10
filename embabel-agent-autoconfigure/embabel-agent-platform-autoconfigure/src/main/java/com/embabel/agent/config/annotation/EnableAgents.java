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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the Embabel Agent framework with auto-configuration support.
 *
 * <p>This annotation triggers the import of agent-related configuration classes
 * and activates appropriate Spring profiles based on the specified attributes.
 * It serves as the foundation for more specialized annotations like
 * {@code @EnableAgentShell} and {@code @EnableAgentMcp}.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * @SpringBootApplication
 * @EnableAgents(
 *     loggingTheme = "starwars",
 *     mcpServers = {"filesystem", "github"}
 * )
 * public class MyAgentApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyAgentApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <h3>Profile Activation:</h3>
 * <p>This annotation activates Spring profiles based on the provided attributes:
 * <ul>
 *   <li>{@code loggingTheme} - Activates a theme-specific profile (e.g., "starwars", "severance")</li>
 *   <li>{@code localModels} - Activates profiles for local AI model providers</li>
 *   <li>{@code mcpServers} - Activates profiles for Model Context Protocol</li>
 * </ul>
 *
 * @author Embabel Team
 * @since 0.1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EnableAgents {

    /**
     * Specifies the logging theme to use for agent operations.
     *
     * <p>If set, this activates a corresponding Spring profile that
     * customizes logging output with themed messages and formatting.
     *
     * <h4>Supported themes:</h4>
     * <ul>
     *   <li>{@code "starwars"} - Star Wars themed logging messages</li>
     *   <li>{@code "severance"} - Corporate/Severance themed logging</li>
     *   <li>{@code ""} (default) - Standard logging without theming</li>
     * </ul>
     *
     * <h4>Example:</h4>
     * <pre>{@code
     * @EnableAgents(loggingTheme = LoggingThemes.STAR_WARS)
     * // Outputs: "May the Force be with your agents!"
     * }</pre>
     *
     * @return the logging theme name, or empty string for default logging
     */
    String loggingTheme() default "";


    /**
     * Specifies Model Context Protocol (MCP) clients to enable.
     *
     * <p>MCP clients allow agents to interact with external tools and
     * data sources through the standardized Model Context Protocol.
     * Each value activates a corresponding integration module.
     *
     * <h4>Example:</h4>
     * <pre>{@code
     * @EnableAgents(mcpClients = {"filesystem", "github", "postgres"})
     * // Enables file system, GitHub, and PostgresSQL MCP clients
     * }</pre>
     *
     * <h4>Security Note:</h4>
     * <p>Each MCP server specified may require additional configuration and
     * authentication. Ensure proper credentials are configured before
     * enabling clients.
     *
     * @return array of MCP server identifiers to enable and connect to
     */
    String[] mcpServers() default {};
}