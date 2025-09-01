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
 * @deprecated This annotation is deprecated in favor of embabel-agent-starter-mcpserver dependency.
 * <p>
 * <b>Migration paths:</b>
 * <br>If using embabel-agent-starter: Replace with embabel-agent-starter-mcpserver
 * <br>Remove this @EnableAgentMcpServer annotation from your application class
 * <p>
 * Agent Mcp Server capabilities will be auto-discovered through convention.
 * <p>
 * This annotation will be removed in version 0.2.0.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Deprecated(since = "0.1.2", forRemoval = true)
public @interface EnableAgentMcpServer {}
