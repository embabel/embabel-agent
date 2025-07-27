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
 * Enables an interactive command-line shell interface for Embabel Agent applications.
 *
 * <p>This annotation configures your Spring Boot application to run in interactive shell mode,
 * providing a REPL-like environment for testing, debugging, and interacting with your agents
 * in real-time. It's the recommended way to develop and test agents before deploying them
 * as services or MCP servers.
 *
 * <h3>What This Provides:</h3>
 * <ul>
 *   <li>Activates the "shell" Spring profile</li>
 *   <li>Configures Spring Shell for interactive command-line interface</li>
 *   <li>Auto-discovers and registers all {@code @Agent} annotated classes</li>
 *   <li>Provides built-in commands for agent interaction and testing</li>
 *   <li>Enables human-in-the-loop interactions and form handling</li>
 *   <li>Sets up progress tracking and detailed logging capabilities</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * @SpringBootApplication
 * @EnableAgentShell
 * public class AgentShellApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(AgentShellApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <h3>Available Shell Commands:</h3>
 * <ul>
 *   <li>{@code help} - Display all available commands</li>
 *   <li>{@code list} - Show all registered agents</li>
 *   <li>{@code execute "<request>" [-p] [-r]} - Run an agent with a request
 *       <ul>
 *         <li>{@code -p} - Log prompts sent to LLMs</li>
 *         <li>{@code -r} - Log responses from LLMs</li>
 *       </ul>
 *   </li>
 *   <li>{@code chat} - Enter interactive chat mode with agents</li>
 *   <li>{@code clear} - Clear the console screen</li>
 *   <li>{@code exit} - Exit the application</li>
 * </ul>
 *
 * <h3>Testing Agents:</h3>
 * <p>The shell mode is ideal for:
 * <ul>
 *   <li>Rapid prototyping of agent workflows</li>
 *   <li>Testing prompt engineering and LLM interactions</li>
 *   <li>Debugging agent action sequences</li>
 *   <li>Demonstrating agent capabilities to stakeholders</li>
 *   <li>Integration testing with real LLM providers</li>
 * </ul>
 *
 * <h3>Tips for Effective Usage:</h3>
 * <ul>
 *   <li>Use {@code -p -r} flags initially to understand agent behavior</li>
 *   <li>Test edge cases interactively before writing unit tests</li>
 *   <li>Use the {@code chat} command for multi-turn conversations</li>
 *   <li>Export shell history for documentation or test cases</li>
 * </ul>
 *
 * @see EnableAgentMcpServer
 * @see EnableAgents
 * @see AgentPlatform
 * @see StartupMode
 * @since 1.0
 * @author Embabel Team
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@AgentPlatform(StartupMode.SHELL)
public @interface EnableAgentShell {}
