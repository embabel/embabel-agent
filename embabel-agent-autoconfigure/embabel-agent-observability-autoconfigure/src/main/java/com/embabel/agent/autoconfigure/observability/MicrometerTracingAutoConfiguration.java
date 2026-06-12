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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Micrometer Tracing bridge to OpenTelemetry.
 *
 * <p>Provides {@link OtelCurrentTraceContext} for context propagation and the standard
 * {@link DefaultTracingObservationHandler} that turns the direct-instrumentation observations
 * (agent turn, action, tool loop, LLM call, tool call) into spans. Span hierarchy comes from
 * Micrometer's current-observation mechanism — no custom handler is needed because {@code observe{}}
 * never leaves a residual scope.
 *
 * @see OpenTelemetrySdkAutoConfiguration
 * @since 0.3.4
 */
@AutoConfiguration(after = OpenTelemetrySdkAutoConfiguration.class)
@ConditionalOnClass({OtelTracer.class, OpenTelemetry.class, ObservationRegistry.class})
@ConditionalOnProperty(prefix = "embabel.observability", name = {"enabled", "tracing-enabled"}, havingValue = "true", matchIfMissing = true)
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
     * Registers the standard Micrometer {@link DefaultTracingObservationHandler}, which bridges
     * observations to OpenTelemetry spans. Spring Boot auto-registers this handler on the
     * ObservationRegistry.
     *
     * @param tracer the Micrometer Tracer
     * @return the standard tracing observation handler
     */
    @Bean
    @ConditionalOnBean(Tracer.class)
    @ConditionalOnMissingBean(DefaultTracingObservationHandler.class)
    public DefaultTracingObservationHandler defaultTracingObservationHandler(Tracer tracer) {
        log.info("Registering standard DefaultTracingObservationHandler for direct Micrometer instrumentation");
        return new DefaultTracingObservationHandler(tracer);
    }
}
