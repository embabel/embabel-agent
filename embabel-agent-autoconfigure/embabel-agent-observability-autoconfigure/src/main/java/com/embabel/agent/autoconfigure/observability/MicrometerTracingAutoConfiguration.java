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
import com.embabel.agent.observability.observation.EmbabelObservationContext;
import com.embabel.agent.observability.observation.EmbabelTracingObservationHandler;
import com.embabel.agent.observability.observation.NonEmbabelTracingObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Micrometer Tracing bridge to OpenTelemetry.
 *
 * <p>Provides OtelCurrentTraceContext for context propagation between Micrometer and OpenTelemetry.
 *
 * @see OpenTelemetrySdkAutoConfiguration
 * @since 0.3.4
 */
@AutoConfiguration(after = OpenTelemetrySdkAutoConfiguration.class)
@ConditionalOnClass({OtelTracer.class, OpenTelemetry.class, ObservationRegistry.class})
@ConditionalOnProperty(prefix = "embabel.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MicrometerTracingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MicrometerTracingAutoConfiguration.class);

    private static final String OBSERVABILITY_TOOL_CALLBACK_OBSERVATION_NAME = "tool call";

    /**
     * Creates OtelCurrentTraceContext for parent-child span propagation.
     *
     * @return the OtelCurrentTraceContext instance
     */
    @Bean
    @ConditionalOnMissingBean(OtelCurrentTraceContext.class)
    public OtelCurrentTraceContext otelCurrentTraceContext() {
        log.debug("Creating OtelCurrentTraceContext for trace context propagation");
        return new OtelCurrentTraceContext();
    }

    /**
     * Registers EmbabelTracingObservationHandler for root span creation and hierarchy management.
     *
     * @param tracerProvider the Micrometer Tracer provider
     * @param otelProvider the OpenTelemetry provider
     * @param properties the observability properties
     * @return the customizer for the ObservationRegistry
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = "implementation",
            havingValue = "SPRING_OBSERVATION", matchIfMissing = true)
    public ObservationRegistryCustomizer<ObservationRegistry> embabelTracingObservationCustomizer(
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<OpenTelemetry> otelProvider,
            ObservabilityProperties properties) {

        return registry -> {
            Tracer tracer = tracerProvider.getIfAvailable();
            OpenTelemetry otel = otelProvider.getIfAvailable();

            if (tracer == null || otel == null) {
                log.warn("Cannot register EmbabelTracingObservationHandler: Tracer or OpenTelemetry not available");
                return;
            }

            io.opentelemetry.api.trace.Tracer otelTracer = otel.getTracer(
                    properties.getTracerName(),
                    properties.getTracerVersion()
            );

            EmbabelTracingObservationHandler handler = new EmbabelTracingObservationHandler(tracer);
            registry.observationConfig().observationHandler(handler);

            log.info("Registered EmbabelTracingObservationHandler for Spring Observation API integration");
        };
    }

    /**
     * Replaces Spring Boot's DefaultTracingObservationHandler with NonEmbabelTracingObservationHandler.
     *
     * <p>This prevents Spring Boot's default handler from processing {@link EmbabelObservationContext},
     * which should be handled exclusively by {@link EmbabelTracingObservationHandler}.
     *
     * @param tracer the Micrometer Tracer
     * @return the configured observation handler
     */
    @Bean
    @ConditionalOnMissingBean(DefaultTracingObservationHandler.class)
    @ConditionalOnProperty(prefix = "embabel.observability", name = "implementation",
            havingValue = "SPRING_OBSERVATION", matchIfMissing = true)
    public DefaultTracingObservationHandler defaultTracingObservationHandler(Tracer tracer) {
        log.info("Replacing Spring Boot's DefaultTracingObservationHandler with NonEmbabelTracingObservationHandler");
        return new NonEmbabelTracingObservationHandler(tracer);
    }

    /**
     * Registers an ObservationPredicate to skip tool call observations from ObservabilityToolCallback
     * when Embabel's own tool tracing is enabled.
     *
     * @return an ObservationRegistryCustomizer that registers the predicate
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = "trace-tool-calls",
            havingValue = "true", matchIfMissing = true)
    public ObservationRegistryCustomizer<ObservationRegistry> skipObservabilityToolCallbackCustomizer() {
        log.info("Registering ObservationPredicate to skip ObservabilityToolCallback observations " +
                "(trace-tool-calls=true, Embabel will trace tools via events)");
        return registry -> registry.observationConfig().observationPredicate(
                (name, context) -> !OBSERVABILITY_TOOL_CALLBACK_OBSERVATION_NAME.equals(name)
        );
    }
}
