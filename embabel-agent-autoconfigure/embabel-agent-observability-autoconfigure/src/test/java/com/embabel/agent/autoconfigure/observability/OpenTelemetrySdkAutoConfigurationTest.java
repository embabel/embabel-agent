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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.ServiceAttributes;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies conditional bean creation and backoff behavior for the OpenTelemetry SDK auto-configuration using focused application contexts.
 */
class OpenTelemetrySdkAutoConfigurationTest {

   /**
    * Base context runner for the OpenTelemetry SDK auto-configuration.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration.class));

   /**
    * Verifies {@code @ConditionalOnMissingBean} backoff by supplying custom beans for all key types and asserting that the auto-configuration reuses those exact instances.
    */
   @Test
   void backsOffWhenCustomBeansProvided() {
      // Arrange
      contextRunner
              .withUserConfiguration(CustomBeansConfig.class)
              // Act
              .run(context -> {
                 // Assert
                 assertThat(context.getBean(Resource.class).getAttribute(ServiceAttributes.SERVICE_NAME))
                         .isEqualTo("custom-service");
                 assertThat(context.getBean(SdkTracerProvider.class))
                         .isSameAs(CustomBeansConfig.CUSTOM_TRACER_PROVIDER);
                 assertThat(context.getBean(OpenTelemetry.class))
                         .isSameAs(CustomBeansConfig.CUSTOM_OPEN_TELEMETRY);
              });
   }

   /**
    * Verifies that the service-name property is bound into the generated OpenTelemetry {@link Resource}, which is the identity attached to emitted telemetry.
    */
   @Test
   void resourceUsesConfiguredServiceName() {
      // Arrange
      contextRunner
              .withPropertyValues("embabel.observability.service-name=test-service")
              // Act
              .run(context -> {
                 // Assert
                 Resource resource = context.getBean(Resource.class);
                 assertThat(resource.getAttribute(ServiceAttributes.SERVICE_NAME)).isEqualTo("test-service");
              });
   }

   /**
    * Verifies the happy path: when at least one exporter and processor are present, the configuration should build a real {@link OpenTelemetrySdk} instance.
    */
   @Test
   void sdkTracerProviderCreatedWithValidExporterAndProcessor() {
      // Arrange
      contextRunner
              .withUserConfiguration(ExporterAndProcessorConfig.class)
              // Act
              .run(context -> {
                 // Assert
                 assertThat(context).hasSingleBean(SdkTracerProvider.class);
                 assertThat(context).hasSingleBean(OpenTelemetry.class);
                 assertThat(context.getBean(OpenTelemetry.class)).isInstanceOf(OpenTelemetrySdk.class);
              });
   }

   /**
    * Verifies that null entries in the supplied exporter and processor lists are ignored instead of preventing the tracer provider from being created.
    */
   @Test
   void sdkTracerProviderIgnoresNullEntriesInProvidedLists() {
      // Arrange
      contextRunner
              .withUserConfiguration(NullAwareExporterConfig.class)
              // Act
              .run(context -> assertThat(context).hasSingleBean(SdkTracerProvider.class));
   }

   /**
    * Verifies the disabled-by-missing-exporters path. The configuration should not expose a tracer provider and should fall back to the noop OpenTelemetry instance.
    */
   @Test
   void sdkTracerProviderNotCreatedWhenNoExportersAvailable() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context.getBeanProvider(SdkTracerProvider.class).getIfAvailable()).isNull();
         assertThat(context).hasSingleBean(OpenTelemetry.class);
         assertThat(context.getBean(OpenTelemetry.class)).isSameAs(OpenTelemetry.noop());
      });
   }

   /**
    * Resets the global OpenTelemetry singleton after each test so one run cannot affect the next through process-wide state.
    */
   @AfterEach
   void tearDown() {

      GlobalOpenTelemetry.resetForTest();
   }

   /**
    * Supplies a minimal non-empty exporter and processor set so the auto-configuration takes the branch that constructs a tracer provider and OpenTelemetry SDK.
    */
   @Configuration
   static class ExporterAndProcessorConfig {

      /**
       * Provides a real in-memory exporter that is sufficient to trigger SDK creation.
       *
       * @return exporter list used by the test context
       */
      @Bean
      List<SpanExporter> spanExporters() {

         return List.of(InMemorySpanExporter.create());
      }

      /**
       * Provides a mocked processor so the configuration can wire processor handling without depending on a concrete external processor implementation.
       *
       * @return processor list used by the test context
       */
      @Bean
      List<SpanProcessor> spanProcessors() {

         return List.of(Mockito.mock(SpanProcessor.class));
      }
   }

   /**
    * Supplies exporter and processor lists that contain nulls so the test can verify the configuration filters those null values out safely.
    */
   @Configuration
   static class NullAwareExporterConfig {

      /**
       * Returns one null and one real exporter to exercise null filtering in exporter handling.
       *
       * @return mixed exporter list with a null entry
       */
      @Bean
      List<SpanExporter> spanExporters() {

         return Arrays.asList(null, InMemorySpanExporter.create());
      }

      /**
       * Returns one null and one mocked processor to exercise null filtering in processor handling.
       *
       * @return mixed processor list with a null entry
       */
      @Bean
      List<SpanProcessor> spanProcessors() {

         return Arrays.asList(null, Mockito.mock(SpanProcessor.class));
      }
   }

   /**
    * Supplies custom beans for every type the auto-configuration would normally create so the test can verify proper backoff behavior.
    */
   @Configuration
   static class CustomBeansConfig {

      static final Resource CUSTOM_RESOURCE = Resource.create(
              io.opentelemetry.api.common.Attributes.of(ServiceAttributes.SERVICE_NAME, "custom-service")
      );

      static final SdkTracerProvider CUSTOM_TRACER_PROVIDER = SdkTracerProvider.builder().build();

      static final OpenTelemetry CUSTOM_OPEN_TELEMETRY = OpenTelemetrySdk.builder()
              .setTracerProvider(CUSTOM_TRACER_PROVIDER)
              .build();

      /**
       * Returns the custom OpenTelemetry instance used to verify OpenTelemetry bean backoff.
       *
       * @return shared custom OpenTelemetry instance
       */
      @Bean
      OpenTelemetry openTelemetry() {

         return CUSTOM_OPEN_TELEMETRY;
      }

      /**
       * Returns the custom telemetry resource used to verify resource backoff.
       *
       * @return shared custom resource instance
       */
      @Bean
      Resource resource() {

         return CUSTOM_RESOURCE;
      }

      /**
       * Returns the custom tracer provider used to verify provider backoff.
       *
       * @return shared custom tracer provider instance
       */
      @Bean
      SdkTracerProvider sdkTracerProvider() {

         return CUSTOM_TRACER_PROVIDER;
      }
   }
}
