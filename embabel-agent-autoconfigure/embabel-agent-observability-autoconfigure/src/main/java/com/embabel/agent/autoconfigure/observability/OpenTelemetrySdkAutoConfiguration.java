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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ServiceAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Objects;

/**
 * Auto-configuration for OpenTelemetry SDK with multi-exporter support.
 *
 * <p>Configures SdkTracerProvider and exports spans to backends (Langfuse, Zipkin, OTLP, etc.).
 * This configuration uses {@link AutoConfigureOrder} with low precedence to ensure
 * it runs AFTER all Spring Boot exporter auto-configurations have created their
 * {@link SpanExporter} beans.
 *
 * @see ObservabilityProperties
 * @since 0.3.4
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE - 10)
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnClass({SdkTracerProvider.class, OpenTelemetry.class})
@ConditionalOnProperty(prefix = "embabel.agent.platform.observability", name = {"enabled", "tracing-enabled"}, havingValue = "true", matchIfMissing = true)
public class OpenTelemetrySdkAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetrySdkAutoConfiguration.class);

    /** Order of the built-in customizer attaching the {@link Resource}. */
    public static final int RESOURCE_CUSTOMIZER_ORDER = 0;

    /** Order of the built-in customizer applying the {@code Sampler}. */
    public static final int SAMPLER_CUSTOMIZER_ORDER = 100;

    /** Order of the built-in customizer attaching the {@code SpanProcessor} beans. */
    public static final int PROCESSOR_CUSTOMIZER_ORDER = 200;

    /** Order of the built-in customizer attaching the {@code SpanExporter} beans. */
    public static final int EXPORTER_CUSTOMIZER_ORDER = 300;

    /**
     * Creates the OpenTelemetry Resource with service name.
     *
     * @param properties the observability configuration properties
     * @return the configured OpenTelemetry Resource
     */
    @Bean
    @ConditionalOnMissingBean(Resource.class)
    public Resource openTelemetryResource(ObservabilityProperties properties) {
        return Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        ServiceAttributes.SERVICE_NAME, properties.getServiceName()
                )));
    }

    /**
     * Attaches the OpenTelemetry {@link Resource} carrying the service identity.
     *
     * @param resource the resource to associate with every span
     * @return the resource customizer
     */
    @Bean
    @Order(RESOURCE_CUSTOMIZER_ORDER)
    public SdkTracerProviderCustomizer embabelResourceCustomizer(Resource resource) {
        return builder -> builder.setResource(resource);
    }

    /**
     * Applies the {@link Sampler} bean, which is what makes {@code management.tracing.sampling.*}
     * effective. Without it the provider keeps its {@code AlwaysOn} default and the configured
     * sampling probability is silently ignored.
     *
     * <p>The sampler is resolved through an {@link ObjectProvider} rather than required as a bean
     * condition: {@code @ConditionalOnBean} across auto-configuration boundaries is order-sensitive,
     * and the sampler is contributed by a different auto-configuration.
     *
     * @param samplerProvider lazy provider for the sampler, absent when no tracing bridge is present
     * @return the sampler customizer
     */
    @Bean
    @Order(SAMPLER_CUSTOMIZER_ORDER)
    public SdkTracerProviderCustomizer embabelSamplerCustomizer(ObjectProvider<Sampler> samplerProvider) {
        return builder -> {
            Sampler sampler = samplerProvider.getIfUnique();
            if (sampler != null) {
                log.debug("Applying Sampler: {}", sampler.getDescription());
                builder.setSampler(sampler);
            }
        };
    }

    /**
     * Attaches every {@code SpanProcessor} bean, whoever contributed it.
     *
     * @param processorsProvider provider for the SpanProcessor beans
     * @return the span processor customizer
     */
    @Bean
    @Order(PROCESSOR_CUSTOMIZER_ORDER)
    public SdkTracerProviderCustomizer embabelSpanProcessorCustomizer(
            ObjectProvider<List<SpanProcessor>> processorsProvider) {
        return builder -> {
            for (SpanProcessor processor : nonNullElements(processorsProvider)) {
                log.debug("Added SpanProcessor: {}", processor.getClass().getSimpleName());
                builder.addSpanProcessor(processor);
            }
        };
    }

    /**
     * Wraps each {@code SpanExporter} bean in its own batch processor, but only when no span
     * processor is present at all. An exporter reaches a backend only through a processor, and
     * Spring Boot's tracing auto-configuration contributes one that wraps <em>every</em> exporter
     * bean; wrapping them again attaches each exporter twice and exports every span twice.
     *
     * <p>Whether a given processor exports a given exporter cannot be determined generically, so
     * this assumes it does. An application whose processor does <em>not</em> export them can attach
     * them itself by contributing a {@link SdkTracerProviderCustomizer} of its own.
     *
     * @param exportersProvider provider for the SpanExporter beans
     * @param processorsProvider provider for the SpanProcessor beans
     * @return the span exporter customizer
     */
    @Bean
    @Order(EXPORTER_CUSTOMIZER_ORDER)
    public SdkTracerProviderCustomizer embabelSpanExporterCustomizer(
            ObjectProvider<List<SpanExporter>> exportersProvider,
            ObjectProvider<List<SpanProcessor>> processorsProvider) {
        return builder -> {
            List<SpanExporter> exporters = nonNullElements(exportersProvider);
            if (!nonNullElements(processorsProvider).isEmpty()) {
                log.info("Delegating export of {} SpanExporter bean(s) to the span processors already present. " +
                        "Contribute an SdkTracerProviderCustomizer if those processors do not export them.",
                        exporters.size());
                return;
            }
            for (SpanExporter exporter : exporters) {
                log.debug("Added SpanExporter: {}", exporter.getClass().getSimpleName());
                builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
            }
        };
    }

    private static <T> List<T> nonNullElements(ObjectProvider<List<T>> provider) {
        List<T> elements = provider.getIfAvailable();
        return elements == null ? List.of() : elements.stream().filter(Objects::nonNull).toList();
    }

    /**
     * Assembles the SdkTracerProvider by applying every {@link SdkTracerProviderCustomizer} in order.
     *
     * @param customizers the ordered customizers contributing each aspect of the provider
     * @param exportersProvider provider for the SpanExporter beans, used only to detect that there
     *                          is nothing to export to
     * @return the configured SdkTracerProvider, or null if no exporters are available
     */
    @Bean
    @ConditionalOnMissingBean(SdkTracerProvider.class)
    public SdkTracerProvider sdkTracerProvider(
            ObjectProvider<SdkTracerProviderCustomizer> customizers,
            ObjectProvider<List<SpanExporter>> exportersProvider) {
        if (nonNullElements(exportersProvider).isEmpty()) {
            log.warn("No SpanExporter beans found. OpenTelemetry tracing will be disabled. " +
                    "To enable tracing, add an exporter dependency (e.g., opentelemetry-exporter-langfuse, " +
                    "opentelemetry-exporter-zipkin) and configure it properly.");
            return null;
        }
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /**
     * Creates the OpenTelemetry SDK instance and registers it globally.
     *
     * @param tracerProviderProvider provider for the SdkTracerProvider bean
     * @return the configured OpenTelemetry instance, or a noop instance if no tracer provider is available
     */
    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry openTelemetry(ObjectProvider<SdkTracerProvider> tracerProviderProvider) {
        SdkTracerProvider tracerProvider = tracerProviderProvider.getIfAvailable();

        if (tracerProvider == null) {
            log.warn("No SdkTracerProvider available. OpenTelemetry will be disabled.");
            return OpenTelemetry.noop();
        }

        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        log.info("OpenTelemetry SDK configured and registered globally");

        return openTelemetry;
    }
}
