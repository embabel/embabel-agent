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

import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the <em>wiring</em> of {@link EmbabelTracingContextPropagationRegistrar} — that the
 * auto-configuration actually creates the bean and that its lifecycle registers/removes
 * {@link ObservationAwareSpanThreadLocalAccessor} on the global {@link ContextRegistry}. The
 * behavioural re-parenting proof lives in {@code DroppedTierCrossThreadReParentTest}, which news up
 * the registrar by hand and therefore cannot catch the registrar silently never being created in a
 * real context (e.g. a wrong condition or an unresolved {@code Tracer}). This test closes that gap.
 *
 * <p>The {@link ContextRegistry} is a process-wide singleton, so each test starts from a known
 * baseline (the accessor key removed) and cleans up afterwards to avoid leaking into other tests.
 */
class MicrometerTracingContextPropagationWiringTest {

    private static final String SPAN_ACCESSOR_KEY = ObservationAwareSpanThreadLocalAccessor.KEY;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MicrometerTracingAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetGlobalContextRegistry() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(SPAN_ACCESSOR_KEY);
    }

    private static boolean spanAccessorRegistered() {
        return ContextRegistry.getInstance().getThreadLocalAccessors().stream()
                .anyMatch(a -> SPAN_ACCESSOR_KEY.equals(a.key()));
    }

    @Test
    @DisplayName("tracer present: registrar bean created, span accessor registered while live, removed on close")
    void registersSpanAccessorWhenTracerPresentAndRemovesOnClose() {
        assertThat(spanAccessorRegistered()).as("baseline: accessor absent before context").isFalse();

        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, TracerConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmbabelTracingContextPropagationRegistrar.class);
                    assertThat(spanAccessorRegistered())
                            .as("accessor registered on the global ContextRegistry while context is live")
                            .isTrue();
                });

        // ApplicationContextRunner closes the context after the consumer => destroy() must have run.
        assertThat(spanAccessorRegistered())
                .as("accessor removed once the context closes (no global leak)")
                .isFalse();
    }

    @Test
    @DisplayName("no tracer: registrar bean still created but no-ops (nothing to propagate)")
    void registrarNoOpsWhenNoTracer() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmbabelTracingContextPropagationRegistrar.class);
                    assertThat(spanAccessorRegistered())
                            .as("no Tracer => no accessor registered")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("tracing-enabled=false: auto-config off, no registrar bean, no accessor")
    void noRegistrarWhenTracingDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, TracerConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.tracing-enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(EmbabelTracingContextPropagationRegistrar.class);
                    assertThat(spanAccessorRegistered()).isFalse();
                });
    }

    @Configuration
    static class ObservationRegistryConfig {
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    @Configuration
    static class TracerConfig {
        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }
}
