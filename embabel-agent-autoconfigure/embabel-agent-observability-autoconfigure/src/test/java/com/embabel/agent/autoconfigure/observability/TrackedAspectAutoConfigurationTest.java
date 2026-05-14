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
package com.embabel.agent.autoconfigure.observability;

import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.agent.observability.observation.TrackedAspect;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the conditional creation rules for the {@link TrackedAspect} auto-configuration, including property-based disablement, missing dependencies, and user-bean backoff.
 */
class TrackedAspectAutoConfigurationTest {

   /**
    * Base context runner for the tracked-aspect auto-configuration.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(TrackedAspectAutoConfiguration.class));

   /**
    * Verifies that the auto-configuration fails fast when the observation registry is absent, which matches the production method signature for the tracked aspect bean.
    */
   @Test
   void contextFailsWithoutObservationRegistry() {
      // Arrange
      contextRunner
              .withUserConfiguration(ObservabilityPropertiesConfig.class)
              // Act
              .run(context -> assertThat(context).hasFailed());
   }

   /**
    * Verifies {@code @ConditionalOnMissingBean} backoff by providing a user-defined tracked aspect and asserting that the auto-configuration does not replace it.
    */
   @Test
   void trackedAspectBacksOffWhenUserBeanExists() {
      // Arrange
      contextRunner
              .withUserConfiguration(TrackedAspectDependenciesConfig.class, CustomTrackedAspectConfig.class)
              // Act
              .run(context -> {
                 // Assert
                 assertThat(context).hasSingleBean(TrackedAspect.class);
                 assertThat(context.getBean(TrackedAspect.class)).isSameAs(CustomTrackedAspectConfig.CUSTOM_TRACKED_ASPECT);
              });
   }

   /**
    * Verifies the happy path: when AspectJ, the observation registry, and observability properties are present, the tracked aspect should be created.
    */
   @Test
   void trackedAspectCreatedWhenDependenciesPresent() {
      // Arrange
      contextRunner
              .withUserConfiguration(TrackedAspectDependenciesConfig.class)
              // Act
              .run(context -> assertThat(context).hasSingleBean(TrackedAspect.class));
   }

   /**
    * Verifies the classpath condition by removing AspectJ from the test class loader and asserting that the tracked aspect is not created.
    */
   @Test
   void trackedAspectNotCreatedWhenAspectjMissing() {
      // Arrange
      contextRunner
              .withClassLoader(new FilteredClassLoader("org.aspectj.lang"))
              .withUserConfiguration(TrackedAspectDependenciesConfig.class)
              // Act
              .run(context -> assertThat(context).doesNotHaveBean(TrackedAspect.class));
   }

   /**
    * Verifies property-based disablement by turning off tracked-operation tracing and asserting that the aspect bean is no longer created.
    */
   @Test
   void trackedAspectNotCreatedWhenPropertyDisabled() {
      // Arrange
      contextRunner
              .withUserConfiguration(TrackedAspectDependenciesConfig.class)
              .withPropertyValues("embabel.observability.trace-tracked-operations=false")
              // Act
              .run(context -> assertThat(context).doesNotHaveBean(TrackedAspect.class));
   }

   /**
    * Supplies the minimal dependencies needed for the tracked-aspect auto-configuration to create its aspect bean.
    */
   @Configuration
   static class TrackedAspectDependenciesConfig {

      /**
       * Provides observability properties with default values for the test context.
       *
       * @return observability properties bean
       */
      @Bean
      ObservabilityProperties observabilityProperties() {

         return new ObservabilityProperties();
      }

      /**
       * Provides an observation registry so the aspect can create observations.
       *
       * @return fresh observation registry for the test context
       */
      @Bean
      ObservationRegistry observationRegistry() {

         return ObservationRegistry.create();
      }
   }

   /**
    * Supplies only observability properties so the test can prove the context fails when the required observation registry dependency is missing.
    */
   @Configuration
   static class ObservabilityPropertiesConfig {

      /**
       * Provides observability properties without the registry dependency.
       *
       * @return observability properties bean
       */
      @Bean
      ObservabilityProperties observabilityProperties() {

         return new ObservabilityProperties();
      }
   }

   /**
    * Supplies a user-defined tracked aspect used to verify that the auto-configuration backs off when the application already defines its own aspect bean.
    */
   @Configuration
   static class CustomTrackedAspectConfig {

      static final TrackedAspect CUSTOM_TRACKED_ASPECT = new TrackedAspect(ObservationRegistry.create(), 7);

      /**
       * Returns the custom tracked aspect instance that should win over the auto-configured one.
       *
       * @return shared custom tracked aspect used in the backoff test
       */
      @Bean
      TrackedAspect trackedAspect() {

         return CUSTOM_TRACKED_ASPECT;
      }
   }
}
