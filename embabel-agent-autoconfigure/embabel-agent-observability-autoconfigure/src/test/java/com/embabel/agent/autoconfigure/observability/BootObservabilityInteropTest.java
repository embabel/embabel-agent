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

import com.embabel.agent.observability.metrics.EmbabelMetricsEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies how Embabel's observability auto-configuration composes with Spring Boot's own
 * observability auto-configurations.
 *
 * <p>Unlike {@link ObservabilityAutoConfigurationTest}, which supplies collaborators such as the
 * {@code MeterRegistry} through {@code withUserConfiguration}, these tests let Boot's real
 * auto-configurations contribute them. That difference is the point: a user configuration is always
 * registered before the auto-configurations under test, so it hides the ordering sensitivity that
 * {@code @ConditionalOnBean} and {@code @ConditionalOnMissingBean} carry across auto-configuration
 * boundaries.
 */
class BootObservabilityInteropTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration.class,
                    org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration.class,
                    org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration.class,
                    org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration.class,
                    org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration.class,
                    org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration.class,
                    org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    ObservabilityAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Nested
    class MetricsListenerWiring {

        @Test
        void metricsListener_shouldBeCreated_whenBootContributesTheMeterRegistry() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(MeterRegistry.class);
                assertThat(context).hasSingleBean(EmbabelMetricsEventListener.class);
            });
        }
    }

    @Nested
    class SpanExportFanOut {

        @Test
        void span_shouldReachEachExporterExactlyOnce() {
            contextRunner
                    .withUserConfiguration(RecordingExporterConfig.class)
                    .withPropertyValues("management.tracing.sampling.probability=1.0")
                    .run(context -> {
                        var exporter = context.getBean(InMemorySpanExporter.class);
                        emitSpan(context.getBean(SdkTracerProvider.class));
                        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
                    });
        }
    }

    @Nested
    class SamplerWiring {

        @Test
        void span_shouldNotBeExported_whenSamplingProbabilityIsZero() {
            contextRunner
                    .withUserConfiguration(RecordingExporterConfig.class)
                    .withPropertyValues("management.tracing.sampling.probability=0.0")
                    .run(context -> {
                        var exporter = context.getBean(InMemorySpanExporter.class);
                        emitSpan(context.getBean(SdkTracerProvider.class));
                        assertThat(exporter.getFinishedSpanItems()).isEmpty();
                    });
        }
    }

    /**
     * Whether a given span processor exports a given exporter cannot be determined generically, so
     * the exporter customizer assumes it does. These cover that assumption being wrong, and the
     * customizer chain being the way out of it.
     */
    @Nested
    class ExportDelegation {

        private final ApplicationContextRunner embabelOnlyRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration.class))
                .withUserConfiguration(RecordingExporterConfig.class, NonExportingProcessorConfig.class);

        @Test
        void exporter_shouldBeSkipped_whenAnotherProcessorIsPresent() {
            embabelOnlyRunner.run(context -> {
                var exporter = context.getBean(InMemorySpanExporter.class);
                emitSpan(context.getBean(SdkTracerProvider.class));
                assertThat(exporter.getFinishedSpanItems()).isEmpty();
            });
        }

        @Test
        void exporter_shouldBeReached_whenApplicationContributesItsOwnCustomizer() {
            embabelOnlyRunner
                    .withUserConfiguration(ApplicationExporterCustomizerConfig.class)
                    .run(context -> {
                        var exporter = context.getBean(InMemorySpanExporter.class);
                        emitSpan(context.getBean(SdkTracerProvider.class));
                        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
                    });
        }
    }

    private static void emitSpan(SdkTracerProvider tracerProvider) {
        tracerProvider.get("interop-test").spanBuilder("unit").startSpan().end();
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingExporterConfig {

        @Bean
        InMemorySpanExporter recordingExporter() {
            return InMemorySpanExporter.create();
        }
    }

    /**
     * A processor that reaches no backend, so delegating the exporters to it loses them.
     */
    @Configuration(proxyBeanMethods = false)
    static class NonExportingProcessorConfig {

        @Bean
        SpanProcessor nonExportingProcessor() {
            return SpanProcessor.composite();
        }
    }

    /**
     * How an application attaches an exporter the built-in customizer has delegated away.
     */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationExporterCustomizerConfig {

        @Bean
        SdkTracerProviderCustomizer applicationExporterCustomizer(InMemorySpanExporter exporter) {
            return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(exporter));
        }
    }
}
