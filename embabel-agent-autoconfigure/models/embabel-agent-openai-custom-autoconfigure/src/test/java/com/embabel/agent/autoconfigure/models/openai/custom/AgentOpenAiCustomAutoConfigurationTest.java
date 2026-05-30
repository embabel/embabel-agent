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
package com.embabel.agent.autoconfigure.models.openai.custom;

import com.embabel.common.ai.autoconfig.ProviderInitialization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the OpenAI-compatible custom-model entrypoint auto-configuration registers user-declared custom models and reports them through provider initialization.
 */
class AgentOpenAiCustomAutoConfigurationTest {

   /**
    * Context runner configured with a test API key and one synthetic model identifier so the test can exercise custom-model registration deterministically.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentOpenAiCustomAutoConfiguration.class))
           .withPropertyValues(
                   "embabel.agent.platform.models.openai.custom.api-key=test-key",
                   "embabel.agent.platform.models.openai.custom.models=test-model"
           );

   /**
    * Confirms that the imported configuration both creates the custom model bean and includes that bean in the provider initialization metadata returned to the platform.
    */
   @Test
   void registersCustomModelAndInitialization() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasSingleBean(ProviderInitialization.class);
         assertThat(context).hasBean("test-model");
         assertThat(context.getBean(ProviderInitialization.class).getRegisteredLlms())
                 .extracting(registeredModel -> registeredModel.getBeanName())
                 .contains("test-model");
      });
   }
}
