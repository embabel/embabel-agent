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
package com.embabel.agent.autoconfigure.models.docker;

import com.embabel.agent.config.models.docker.DockerConnectionProperties;
import com.embabel.agent.config.models.docker.DockerLocalModelsConfig;
import com.embabel.agent.config.models.docker.DockerRetryProperties;
import com.embabel.common.ai.autoconfig.ProviderInitialization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link AgentDockerModelsAutoConfiguration}. Verifies that the autoconfiguration loads correctly, registers expected beans, and applies default and custom property
 * values.
 *
 * @since 0.4.0
 */
class AgentDockerModelsAutoConfigurationTest {

   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentDockerModelsAutoConfiguration.class));

   /**
    * Verifies the context starts successfully even when the Docker endpoint is unreachable.
    */
   @Test
   void contextLoadsWhenDockerEndpointIsUnavailable() {

      contextRunner.run(context -> assertThat(context).hasNotFailed());
   }

   /**
    * Verifies that a custom {@code embabel.agent.models.docker.base-url} property is applied.
    */
   @Test
   void dockerConnectionPropertiesCustomBaseUrlApplied() {

      contextRunner.withPropertyValues("embabel.agent.models.docker.base-url=http://custom-docker:12434/engines").run(context -> {
         final DockerConnectionProperties props = context.getBean(DockerConnectionProperties.class);
         assertThat(props.getBaseUrl()).isEqualTo("http://custom-docker:12434/engines");
      });
   }

   /**
    * Verifies the default base URL is {@code http://localhost:12434/engines}.
    */
   @Test
   void dockerConnectionPropertiesDefaultBaseUrl() {

      contextRunner.run(context -> {
         final DockerConnectionProperties props = context.getBean(DockerConnectionProperties.class);
         assertThat(props.getBaseUrl()).isEqualTo("http://localhost:12434/engines");
      });
   }

   /**
    * Verifies that a {@link DockerLocalModelsConfig} bean is registered in the context.
    */
   @Test
   void dockerLocalModelsConfigBeanIsRegistered() {

      contextRunner.run(context -> assertThat(context).hasSingleBean(DockerLocalModelsConfig.class));
   }

   /**
    * Verifies that a {@link ProviderInitialization} bean is registered by the initializer.
    */
   @Test
   void dockerLocalModelsInitializerBeanIsRegistered() {

      contextRunner.run(context -> assertThat(context).hasSingleBean(ProviderInitialization.class));
   }

   /**
    * Verifies that a custom {@code embabel.agent.platform.models.docker.max-attempts} property is applied.
    */
   @Test
   void dockerRetryPropertiesCustomMaxAttemptsApplied() {

      contextRunner.withPropertyValues("embabel.agent.platform.models.docker.max-attempts=3").run(context -> {
         final DockerRetryProperties props = context.getBean(DockerRetryProperties.class);
         assertThat(props.getMaxAttempts()).isEqualTo(3);
      });
   }

   /**
    * Verifies the default backoff interval is 5000 ms.
    */
   @Test
   void dockerRetryPropertiesDefaultBackoffMillis() {

      contextRunner.run(context -> {
         final DockerRetryProperties props = context.getBean(DockerRetryProperties.class);
         assertThat(props.getBackoffMillis()).isEqualTo(5000L);
      });
   }

   /**
    * Verifies the default maximum retry attempts is 10.
    */
   @Test
   void dockerRetryPropertiesDefaultMaxAttempts() {

      contextRunner.run(context -> {
         final DockerRetryProperties props = context.getBean(DockerRetryProperties.class);
         assertThat(props.getMaxAttempts()).isEqualTo(10);
      });
   }

   /**
    * Verifies that the {@code @PostConstruct} log method completes without throwing.
    */
   @Test
   void logEventDoesNotThrow() {

      assertThatCode(() -> new AgentDockerModelsAutoConfiguration().logEvent()).doesNotThrowAnyException();
   }

   /**
    * Verifies that the provider name in {@link ProviderInitialization} is {@code "Docker"}.
    */
   @Test
   void providerInitializationHasDockerProvider() {

      contextRunner.run(context -> {
         final ProviderInitialization initialization = context.getBean(ProviderInitialization.class);
         assertThat(initialization.getProvider()).isEqualTo("Docker");
      });
   }

   /**
    * Verifies that no LLMs or embeddings are registered when the Docker endpoint is unreachable.
    */
   @Test
   void providerInitializationHasEmptyModelListWhenDockerUnavailable() {

      contextRunner.run(context -> {
         final ProviderInitialization initialization = context.getBean(ProviderInitialization.class);
         assertThat(initialization.getRegisteredLlms()).isEmpty();
         assertThat(initialization.getRegisteredEmbeddings()).isEmpty();
      });
   }

   @Override
   public String toString() {

      return "AgentDockerModelsAutoConfigurationTest{" +
             "contextRunner=" + contextRunner +
             '}';
   }
}
