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

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Micrometer Tracing bridge to OpenTelemetry.
 *
 * <p>Provides {@link OtelCurrentTraceContext} for context propagation. The observations produced by
 * the direct instrumentation (agent turn, action, tool loop, LLM call, tool call) are turned into
 * spans by Spring Boot's own standard {@link DefaultTracingObservationHandler} — Embabel registers no
 * handler of its own: with direct {@code observe{}} instrumentation the span hierarchy comes from
 * Micrometer's current-observation mechanism (no residual scope), so the stock handler suffices.
 *
 * <p><b>Cross-thread caveat.</b> The current-observation mechanism alone is sufficient only on a
 * single thread. When work is dispatched to another thread (e.g. an {@code ExecutorAsyncer} pool) and
 * the nearest live ancestor is reached through a tier suppressed by the tier filter (its observation
 * is a no-op carrying no span), only the live span — via {@code tracer.currentSpan()} — links the
 * child to its parent, and that thread local is not propagated by the observation accessor. The
 * {@link EmbabelTracingContextPropagationRegistrar} registers
 * {@code ObservationAwareSpanThreadLocalAccessor} so the live span also crosses the boundary,
 * preventing orphaned child spans (e.g. an LLM span starting a new root trace under a dropped action).
 *
 * @see OpenTelemetrySdkAutoConfiguration
 * @see EmbabelTracingContextPropagationRegistrar
 * @since 0.3.4
 */
@AutoConfiguration(after = OpenTelemetrySdkAutoConfiguration.class)
@ConditionalOnClass({OtelTracer.class, OpenTelemetry.class, ObservationRegistry.class})
@ConditionalOnProperty(prefix = "embabel.agent.platform.observability", name = {"enabled", "tracing-enabled"}, havingValue = "true", matchIfMissing = true)
public class MicrometerTracingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MicrometerTracingAutoConfiguration.class);

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
     * Registers {@code ObservationAwareSpanThreadLocalAccessor} so the live span crosses thread
     * boundaries, keeping child spans (e.g. async LLM calls under a suppressed action) in the same
     * trace as their live ancestor. Requires a {@link Tracer}; if none is present (no tracing bridge)
     * there is nothing to propagate.
     *
     * <p>The {@link Tracer} is resolved lazily through an {@link ObjectProvider} rather than required
     * as a bean condition: {@code @ConditionalOnBean} across auto-configuration boundaries is
     * order-sensitive and would silently skip this registrar if Boot's tracer bean is defined later.
     * The provider defers resolution to instantiation time, so the registrar simply no-ops when no
     * tracer is present.
     *
     * @param observationRegistry the observation registry
     * @param tracerProvider lazy provider for the tracer whose current span must cross threads
     * @return the registrar (registers on init, deregisters on shutdown)
     */
    @Bean
    @ConditionalOnMissingBean(EmbabelTracingContextPropagationRegistrar.class)
    public EmbabelTracingContextPropagationRegistrar embabelTracingContextPropagationRegistrar(
            ObservationRegistry observationRegistry, ObjectProvider<Tracer> tracerProvider) {
        return new EmbabelTracingContextPropagationRegistrar(observationRegistry, tracerProvider.getIfAvailable());
    }
}
