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
package com.embabel.agent.autoconfigure.models.dashscope;

import com.embabel.agent.spi.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the DashScope entrypoint auto-configuration imports the provider config
 * and exposes the configured Qwen model beans when credentials are present.
 */
class AgentDashScopeAutoConfigurationTest {

   /**
    * Context runner configured with a test API key so the provider configuration can
    * instantiate its model beans without depending on real environment variables.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentDashScopeAutoConfiguration.class))
           .withPropertyValues("embabel.agent.platform.models.dashscope.api-key=test-key");

   /**
    * Confirms that all DashScope Qwen model beans are registered and backed by the
    * generic LLM service abstraction exposed to the rest of the platform.
    */
   @Test
   void registersDashScopeModelBeans() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasBean("dashscopeQwen37Max");
         assertThat(context).hasBean("dashscopeQwen37Plus");
         assertThat(context).hasBean("dashscopeQwen37Flash");
         assertThat(context.getBean("dashscopeQwen37Max")).isInstanceOf(LlmService.class);
         assertThat(context.getBean("dashscopeQwen37Plus")).isInstanceOf(LlmService.class);
         assertThat(context.getBean("dashscopeQwen37Flash")).isInstanceOf(LlmService.class);
      });
   }
}
