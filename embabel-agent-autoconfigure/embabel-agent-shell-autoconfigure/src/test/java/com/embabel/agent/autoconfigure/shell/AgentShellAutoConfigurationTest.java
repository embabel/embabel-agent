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
package com.embabel.agent.autoconfigure.shell;

import com.embabel.agent.api.common.Asyncer;
import com.embabel.agent.api.common.ToolsStats;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.shell.TerminalServices;
import com.embabel.agent.shell.config.ShellProperties;
import com.embabel.agent.spi.logging.ColorPalette;
import com.embabel.agent.spi.logging.LoggingPersonality;
import com.embabel.common.ai.model.ModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.jline.PromptProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the shell auto-configuration contributes its core shell beans and respects the {@code @ConditionalOnMissingBean} fallback around the prompt provider.
 */
class AgentShellAutoConfigurationTest {

   /**
    * Context runner with mocked shell collaborators so component scanning can create {@code ShellCommands} and related shell beans without a real terminal or agent runtime.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentShellAutoConfiguration.class))
           .withBean(Terminal.class, () -> mock(Terminal.class))
           .withBean(Autonomy.class, () -> {
              Autonomy autonomy = mock(Autonomy.class);
              when(autonomy.getAgentPlatform()).thenReturn(mock(AgentPlatform.class));
              return autonomy;
           })
           .withBean(Asyncer.class, () -> mock(Asyncer.class))
           .withBean(ToolsStats.class, () -> mock(ToolsStats.class))
           .withBean(ColorPalette.class, () -> mock(ColorPalette.class))
           .withBean(LoggingPersonality.class, () -> mock(LoggingPersonality.class))
           .withBean(ModelProvider.class, () -> mock(ModelProvider.class))
           .withBean(ObjectMapper.class, ObjectMapper::new);

   /**
    * Confirms that a user-supplied {@link PromptProvider} suppresses the fallback bean, proving the {@code @ConditionalOnMissingBean} behavior rather than metadata only.
    */
   @Test
   void backsOffFallbackPromptProviderWhenCustomPromptProviderExists() {
      // Arrange
      contextRunner
              .withUserConfiguration(CustomPromptProviderConfig.class)
              // Act
              .run(context -> assertThat(context.getBean(PromptProvider.class))
                      // Assert
                      .isSameAs(CustomPromptProviderConfig.CUSTOM_PROMPT_PROVIDER));
   }

   /**
    * Confirms the default shell setup. The context should bind shell properties, expose terminal services, and provide the fallback prompt provider.
    */
   @Test
   void registersShellBeansAndFallbackPromptProvider() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasSingleBean(ShellProperties.class);
         assertThat(context).hasSingleBean(TerminalServices.class);
         assertThat(context).hasSingleBean(PromptProvider.class);
      });
   }

   /**
    * Test configuration that supplies a custom prompt provider so the auto-configuration can be checked for proper backoff behavior.
    */
   @Configuration
   static class CustomPromptProviderConfig {

      static final PromptProvider CUSTOM_PROMPT_PROVIDER = () -> null;

      /**
       * Supplies the custom prompt provider instance that should win over the default one.
       *
       * @return the test prompt provider used to verify conditional backoff
       */
      @Bean
      PromptProvider promptProvider() {

         return CUSTOM_PROMPT_PROVIDER;
      }
   }
}
