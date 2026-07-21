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

import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.mcpserver.health.McpServerHealthEvaluator;
import com.embabel.agent.mcpserver.health.McpServerInitializationTracker;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpServerActuatorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentMcpServerAutoConfiguration.class,
                    McpServerActuatorAutoConfiguration.class))
            .withBean(Autonomy.class, () -> {
                Autonomy autonomy = mock(Autonomy.class);
                when(autonomy.getAgentPlatform()).thenReturn(mock(AgentPlatform.class));
                return autonomy;
            })
            .withBean(McpSyncServer.class, () -> mock(McpSyncServer.class))
            .withBean(McpAsyncServer.class, () -> mock(McpAsyncServer.class));

    @Test
    void registersMcpServerHealthIndicatorByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(McpServerInitializationTracker.class);
            assertThat(context).hasSingleBean(McpServerHealthEvaluator.class);
            assertThat(context).hasBean("mcpServer");
            assertThat(context.getBean("mcpServer")).isInstanceOf(HealthIndicator.class);
        });
    }

    @Test
    void skipsHealthIndicatorWhenDisabled() {
        contextRunner
                .withPropertyValues("embabel.agent.mcpserver.health.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpServerInitializationTracker.class);
                    assertThat(context).doesNotHaveBean("mcpServer");
                });
    }
}
