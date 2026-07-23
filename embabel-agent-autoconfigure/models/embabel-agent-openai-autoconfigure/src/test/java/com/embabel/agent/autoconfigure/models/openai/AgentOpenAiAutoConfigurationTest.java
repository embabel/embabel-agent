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
package com.embabel.agent.autoconfigure.models.openai;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies OpenAI model auto-configuration wiring that is specific to the Spring AI OpenAI provider.
 */
class AgentOpenAiAutoConfigurationTest {

   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentOpenAiAutoConfiguration.class))
           .withPropertyValues("embabel.agent.platform.models.openai.api-key=test-key");

   /**
    * Verifies that the standard OpenAI auto-configuration configures the catalog model id on the chat model, which
    * {@link SpringAiLlmService} binds onto request-level options at call time.
    */
   @Test
   void openAiModelServiceBindsConfiguredModelOnRequestOptions() {
      // Prepare
      final String beanName = "gpt41";
      final String expectedModel = "gpt-4.1";

      // Execute
      contextRunner.run(context -> {
         final SpringAiLlmService service = context.getBean(beanName, SpringAiLlmService.class);

         // Verify
         assertThat(service.getChatModel().getOptions().getModel()).isEqualTo(expectedModel);
      });
   }
}
