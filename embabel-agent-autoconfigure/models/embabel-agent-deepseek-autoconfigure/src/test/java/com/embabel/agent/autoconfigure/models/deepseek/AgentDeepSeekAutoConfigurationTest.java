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
package com.embabel.agent.autoconfigure.models.deepseek;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the DeepSeek entrypoint auto-configuration imports the provider config and exposes the expected model beans when an API key is supplied.
 */
class AgentDeepSeekAutoConfigurationTest {

   /**
    * Context runner configured with a test API key so the DeepSeek configuration can instantiate its beans without requiring environment variables.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentDeepSeekAutoConfiguration.class))
           .withPropertyValues("embabel.agent.platform.models.deepseek.api-key=test-key");

   /**
    * Confirms that both DeepSeek LLM beans are created through the imported configuration and that each bean is backed by the Spring AI LLM service wrapper.
    */
   @Test
   void registersDeepSeekModelBeans() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasBean("deepSeekChat");
         assertThat(context).hasBean("deepSeekReasoner");
         assertThat(context.getBean("deepSeekChat")).isInstanceOf(SpringAiLlmService.class);
         assertThat(context.getBean("deepSeekReasoner")).isInstanceOf(SpringAiLlmService.class);
      });
   }
}
