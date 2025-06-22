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
 * Enable MCP Profiles
 */
@Retention(RetentionPolicy.RUNTIME) // Keep the annotation at runtime for reflection
@Target(ElementType.TYPE)
@EnableAgents("mcp-server")
public @interface EnableAgentMcp {
    /**
     * Optional logging theme for the MCP agent.
     * Default is "severance".
     */
    String loggingTheme() default "severance";
}
