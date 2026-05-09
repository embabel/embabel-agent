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
import com.embabel.agent.mcpserver.async.config.McpAsyncServerConfiguration;
import com.embabel.agent.mcpserver.sync.config.McpSyncServerConfiguration;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the MCP server autoconfiguration selects the correct sync or async server configuration based on the configured execution mode.
 */
class AgentMcpServerAutoConfigurationTest {

   /**
    * Application context used to exercise the component-scanned MCP server wiring. The mocked autonomy and server beans satisfy collaborators required by the scanned publishers
    * without involving a real MCP runtime.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentMcpServerAutoConfiguration.class))
           .withBean(Autonomy.class, () -> {
              Autonomy autonomy = mock(Autonomy.class);
              when(autonomy.getAgentPlatform()).thenReturn(mock(AgentPlatform.class));
              return autonomy;
           })
           .withBean(McpSyncServer.class, () -> mock(McpSyncServer.class))
           .withBean(McpAsyncServer.class, () -> mock(McpAsyncServer.class));

   /**
    * Verifies that setting the MCP mode to {@code ASYNC} switches the autoconfiguration over to the async configuration and async banner callback.
    */
   @Test
   void registersAsyncMcpConfigurationWhenConfigured() {
      // Arrange
      contextRunner
              .withPropertyValues("spring.ai.mcp.server.type=ASYNC")
              // Act
              .run(context -> {
                 // Assert
                 assertThat(context).hasSingleBean(McpAsyncServerConfiguration.class);
                 assertThat(context).doesNotHaveBean(McpSyncServerConfiguration.class);
                 assertThat(context).hasBean("asyncBannerCallback");
                 assertThat(context.getBean("asyncBannerCallback")).isInstanceOf(ToolCallbackProvider.class);
              });
   }

   /**
    * Verifies the default branch of the autoconfiguration. Without an explicit mode, the sync configuration should load and publish its sync banner callback.
    */
   @Test
   void registersSyncMcpConfigurationByDefault() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasSingleBean(McpSyncServerConfiguration.class);
         assertThat(context).doesNotHaveBean(McpAsyncServerConfiguration.class);
         assertThat(context).hasBean("syncBannerCallback");
         assertThat(context.getBean("syncBannerCallback")).isInstanceOf(ToolCallbackProvider.class);
      });
   }
}
