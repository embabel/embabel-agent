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
package com.embabel.agent.autoconfigure.models.mistralai;

import com.embabel.common.ai.autoconfig.ProviderInitialization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Mistral AI entrypoint auto-configuration loads the bundled model metadata and turns it into provider initialization output when credentials are present.
 */
class AgentMistralAiAutoConfigurationTest {

   /**
    * Context runner configured with a test API key so the Mistral configuration can build its model registry without relying on external environment setup.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentMistralAiAutoConfiguration.class))
           .withPropertyValues("embabel.agent.platform.models.mistralai.api-key=test-key");

   /**
    * Confirms that the imported Mistral configuration produces a provider initialization bean and that the bundled YAML metadata results in at least one registered model.
    */
   @Test
   void registersProviderInitializationFromBundledMetadata() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasSingleBean(ProviderInitialization.class);
         assertThat(context.getBean(ProviderInitialization.class).getRegisteredLlms()).isNotEmpty();
      });
   }
}
