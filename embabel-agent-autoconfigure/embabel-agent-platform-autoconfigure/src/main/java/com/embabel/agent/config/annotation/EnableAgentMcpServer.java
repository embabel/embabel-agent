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
 * Enables an Embabel Agent application to run as a Model Context Protocol (MCP) server.
 *
 * <p>This annotation configures your Spring Boot application to expose agents as
 * MCP-compatible tools that can be consumed by AI assistants like Claude Desktop,
 * IDEs with MCP support, or other MCP-compliant clients.
 *
 * <h3>What This Annotation Provides:</h3>
 * <ul>
 *   <li>Activates the "mcp-server" Spring profile</li>
 *   <li>Configures JSON-RPC server for MCP protocol communication</li>
 *   <li>Auto-discovers and exposes {@code @Agent} annotated classes as MCP tools</li>
 *   <li>Sets up security boundaries and sandboxing for tool execution</li>
 *   <li>Enables MCP protocol handlers and message routing</li>
 * </ul>
 *
 * <h3>Configuration Properties:</h3>
 * <p>When this annotation is active, the following properties can be configured:
 * <pre>{@code
 * # application.yml
 * mcp:
 *   server:
 *     port: 3000              # MCP server port (default: 3000)
 *     timeout: 30000          # Request timeout in ms (default: 30s)
 *     max-connections: 10     # Max concurrent connections
 *     allowed-tools: "*"      # Tool access control (* = all)
 * }</pre>
 *
 * @see EnableAgentShell
 * @see EnableAgents
 * @see AgentPlatform
 * @since 1.0
 * @author Embabel Team
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@AgentPlatform(StartupMode.MCP_SERVER)
public @interface EnableAgentMcpServer {}