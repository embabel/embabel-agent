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
package com.embabel.agent.autoconfigure.models.minimax;

import com.embabel.agent.spi.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the MiniMax entrypoint auto-configuration imports the provider config and exposes both configured MiniMax model beans when credentials are present.
 */
class AgentMiniMaxAutoConfigurationTest {

   /**
    * Context runner configured with a test API key so the provider configuration can instantiate its model beans without depending on real environment variables.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentMiniMaxAutoConfiguration.class))
           .withPropertyValues("embabel.agent.platform.models.minimax.api-key=test-key");

   /**
    * Confirms that both MiniMax model beans are registered and backed by the generic LLM service abstraction exposed to the rest of the platform.
    */
   @Test
   void registersMiniMaxModelBeans() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasBean("miniMaxM27");
         assertThat(context).hasBean("miniMaxM27Highspeed");
         assertThat(context.getBean("miniMaxM27")).isInstanceOf(LlmService.class);
         assertThat(context.getBean("miniMaxM27Highspeed")).isInstanceOf(LlmService.class);
      });
   }
}
