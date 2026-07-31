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

import com.embabel.agent.mcpserver.domain.ServerHealthStatus;
import com.embabel.agent.mcpserver.health.McpServerHealthEvaluator;
import com.embabel.agent.mcpserver.health.McpServerInitializationState;
import com.embabel.agent.mcpserver.health.McpServerInitializationTracker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator health indicator for the Embabel MCP server.
 *
 * <p>Exposed as the {@code mcpServer} component under {@code /actuator/health}.
 */
public class McpServerHealthIndicator implements HealthIndicator {

    private final McpServerHealthEvaluator evaluator;
    private final McpServerInitializationTracker tracker;

    public McpServerHealthIndicator(
            McpServerHealthEvaluator evaluator,
            McpServerInitializationTracker tracker) {
        this.evaluator = evaluator;
        this.tracker = tracker;
    }

    @Override
    public Health health() {
        ServerHealthStatus status = evaluator.evaluate();
        McpServerInitializationState state = tracker.getState();

        Health.Builder builder = switch (state) {
            case NOT_STARTED, INITIALIZING -> Health.outOfService();
            case FAILED -> Health.down();
            case READY -> status.isHealthy() ? Health.up() : Health.down();
        };

        return builder
                .withDetail("mode", status.getMode().name())
                .withDetail("toolCount", status.getToolCount())
                .withDetail("initializationState", state.name())
                .withDetail("timestamp", status.getTimestamp().toString())
                .withDetail("issues", status.getIssues())
                .build();
    }
}
