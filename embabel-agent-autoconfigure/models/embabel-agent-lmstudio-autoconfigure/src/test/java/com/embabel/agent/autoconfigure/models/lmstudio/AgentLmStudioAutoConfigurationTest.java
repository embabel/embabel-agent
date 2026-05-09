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
package com.embabel.agent.autoconfigure.models.lmstudio;

import com.embabel.agent.config.models.lmstudio.LmStudioProperties;
import com.embabel.common.ai.autoconfig.ProviderInitialization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the LM Studio entrypoint auto-configuration binds its properties and still initializes cleanly when model discovery cannot reach an LM Studio server.
 */
class AgentLmStudioAutoConfigurationTest {

   /**
    * Context runner pointed at an intentionally unavailable local endpoint so the test can exercise the graceful fallback path without any external dependency.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentLmStudioAutoConfiguration.class))
           .withPropertyValues("embabel.agent.platform.models.lmstudio.base-url=http://127.0.0.1:1");

   /**
    * Confirms that the auto-configuration binds LM Studio properties, produces a provider initialization bean, and reports no discovered models when the endpoint is unreachable.
    */
   @Test
   void registersLmStudioPropertiesAndEmptyInitializationWhenEndpointUnavailable() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasSingleBean(LmStudioProperties.class);
         assertThat(context).hasSingleBean(ProviderInitialization.class);
         assertThat(context.getBean(ProviderInitialization.class).getRegisteredLlms()).isEmpty();
         assertThat(context.getBean(ProviderInitialization.class).getRegisteredEmbeddings()).isEmpty();
      });
   }
}
