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
package com.embabel.agent.autoconfigure.mcpserver;

import com.embabel.agent.mcpserver.health.McpServerHealthEvaluator;
import com.embabel.agent.mcpserver.health.McpServerInitializationTracker;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers an Actuator {@link HealthIndicator} for the MCP server when Actuator is present.
 *
 * <p>Does not create a hard dependency on Actuator for consumers of
 * {@code embabel-agent-starter-mcpserver}; Actuator must be on the classpath separately.
 */
@AutoConfiguration(after = AgentMcpServerAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(McpServerHealthEvaluator.class)
@ConditionalOnProperty(
        prefix = "embabel.agent.mcpserver.health",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class McpServerActuatorAutoConfiguration {

    @Bean(name = "mcpServer")
    @ConditionalOnMissingBean(name = "mcpServer")
    public HealthIndicator mcpServerHealthIndicator(
            McpServerHealthEvaluator evaluator,
            McpServerInitializationTracker tracker) {
        return new McpServerHealthIndicator(evaluator, tracker);
    }
}
