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

import com.embabel.agent.mcpserver.domain.McpExecutionMode;
import com.embabel.agent.mcpserver.domain.ServerHealthStatus;
import com.embabel.agent.mcpserver.health.McpServerHealthEvaluator;
import com.embabel.agent.mcpserver.health.McpServerInitializationState;
import com.embabel.agent.mcpserver.health.McpServerInitializationTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpServerHealthIndicatorTest {

    @Test
    void reportsOutOfServiceWhileInitializing() {
        McpServerInitializationTracker tracker = new McpServerInitializationTracker();
        tracker.markInitializing();
        McpServerHealthEvaluator evaluator = mock(McpServerHealthEvaluator.class);
        when(evaluator.evaluate()).thenReturn(new ServerHealthStatus(
                false, McpExecutionMode.SYNC, 0, List.of("MCP server is still initializing"), Instant.now()));

        Health health = new McpServerHealthIndicator(evaluator, tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("initializationState", "INITIALIZING");
        assertThat(health.getDetails()).containsEntry("mode", "SYNC");
        assertThat(health.getDetails()).containsKey("toolCount");
        assertThat(health.getDetails()).containsKey("timestamp");
        assertThat(health.getDetails()).containsKey("issues");
    }

    @Test
    void reportsUpWhenReadyAndHealthy() {
        McpServerInitializationTracker tracker = new McpServerInitializationTracker();
        tracker.markInitializing();
        tracker.markReady();
        McpServerHealthEvaluator evaluator = mock(McpServerHealthEvaluator.class);
        when(evaluator.evaluate()).thenReturn(new ServerHealthStatus(
                true, McpExecutionMode.ASYNC, 3, List.of(), Instant.now()));

        Health health = new McpServerHealthIndicator(evaluator, tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("initializationState", "READY");
        assertThat(health.getDetails()).containsEntry("mode", "ASYNC");
        assertThat(health.getDetails()).containsEntry("toolCount", 3);
    }

    @Test
    void reportsDownWhenFailed() {
        McpServerInitializationTracker tracker = new McpServerInitializationTracker();
        tracker.markInitializing();
        tracker.markFailed("boom");
        McpServerHealthEvaluator evaluator = mock(McpServerHealthEvaluator.class);
        when(evaluator.evaluate()).thenReturn(new ServerHealthStatus(
                false, McpExecutionMode.SYNC, 1, List.of("boom"), Instant.now()));

        Health health = new McpServerHealthIndicator(evaluator, tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("initializationState", "FAILED");
    }

    @Test
    void reportsDownWhenReadyButUnhealthy() {
        McpServerInitializationTracker tracker = new McpServerInitializationTracker();
        tracker.markInitializing();
        tracker.markReady();
        McpServerHealthEvaluator evaluator = mock(McpServerHealthEvaluator.class);
        when(evaluator.evaluate()).thenReturn(new ServerHealthStatus(
                false, McpExecutionMode.SYNC, 0, List.of("Registered tool count 0 is below minimum 1"), Instant.now()));

        Health health = new McpServerHealthIndicator(evaluator, tracker).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("initializationState", "READY");
    }
}
