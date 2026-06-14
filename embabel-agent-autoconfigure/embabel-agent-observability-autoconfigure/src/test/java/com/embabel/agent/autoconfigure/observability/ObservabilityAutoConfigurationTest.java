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
import com.embabel.agent.observability.metrics.EmbabelMetricsEventListener;
import com.embabel.agent.observability.observation.ChatModelObservationFilter;
import com.embabel.agent.observability.observation.EmbabelSpanEventListener;
import com.embabel.agent.observability.mdc.MdcPropagationEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests conditional bean creation in ObservabilityAutoConfiguration: which beans are created or
 * skipped based on the {@code embabel.agent.platform.observability.*} properties (the {@code enabled} /
 * {@code tracing-enabled} / {@code metrics-enabled} switches and the per-tier {@code trace-*}
 * toggles) and the available dependencies ({@code ObservationRegistry}, {@code MeterRegistry}).
 */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    // --- Span conventions customizer creation ---

    private static final String CONVENTIONS_BEAN = "embabelSpanConventionsCustomizer";

    private static final String TIER_FILTER_BEAN = "embabelTierFilterCustomizer";

    @Test
    void spanConventionsCustomizer_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasBean(CONVENTIONS_BEAN);
                });
    }

    @Test
    void spanConventionsCustomizer_shouldNotBeCreated_whenDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CONVENTIONS_BEAN);
                });
    }

    @Test
    void spanConventionsCustomizer_shouldNotBeCreated_whenTraceAgentEventsDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.trace-agent-events=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CONVENTIONS_BEAN);
                });
    }

    // --- AgentInstrumentation adapter (the core's instrumentation port) ---
    // Master-switch guarantee: with the module disabled, the Micrometer ADAPTER is NOT created, so the
    // core falls back to its own NoOpAgentInstrumentation bean => no embabel agent/action/llm/tool-loop
    // spans. Asserted BY BEAN NAME (not by the AgentInstrumentation type) on purpose: the core's NoOp
    // bean is also an AgentInstrumentation, so a type assertion would be both imprecise and fragile —
    // the bean name pins it to the adapter alone. A real ObservationRegistry is supplied in every case,
    // so a missing-bean result proves the FLAG gates the adapter, not an absent registry.

    private static final String ADAPTER_BEAN = "embabelAgentInstrumentation";

    @Test
    void agentInstrumentationAdapter_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasBean(ADAPTER_BEAN);
                });
    }

    @Test
    void agentInstrumentationAdapter_shouldNotBeCreated_whenObservabilityDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.enabled=false")
                .run(context -> {
                    // enabled=false disables the whole auto-config class => no adapter => core stays NoOp.
                    assertThat(context).doesNotHaveBean(ADAPTER_BEAN);
                });
    }

    @Test
    void agentInstrumentationAdapter_shouldNotBeCreated_whenTracingDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.tracing-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ADAPTER_BEAN);
                });
    }

    // --- Tier-filter predicate customizer ---

    @Test
    void tierFilterCustomizer_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasBean(TIER_FILTER_BEAN);
                });
    }

    @Test
    void tierFilterCustomizer_isStillCreated_whenTracingDisabled_soItCanSuppressEmbabelSpans() {
        // The master switch tracing-enabled=false must SUPPRESS Embabel spans even when another
        // tracing source (Spring Boot) is active. That suppression is done by this predicate, so the
        // bean must remain registered (gated only by the module `enabled`), not be skipped.
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.tracing-enabled=false")
                .run(context -> {
                    assertThat(context).hasBean(TIER_FILTER_BEAN);
                });
    }

    @Test
    void tierFilterCustomizer_shouldNotBeCreated_whenModuleDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TIER_FILTER_BEAN);
                });
    }

    // --- Point-event span listener ---

    @Test
    void spanEventListener_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbabelSpanEventListener.class);
                });
    }

    @Test
    void spanEventListener_shouldNotBeCreated_whenTracingDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.tracing-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EmbabelSpanEventListener.class);
                });
    }

    @Test
    void spanEventListener_shouldNotBeCreated_whenNoObservationRegistry() {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EmbabelSpanEventListener.class);
                });
    }

    // --- ChatModel filter ---

    @Test
    void chatModelFilter_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModelObservationFilter.class);
                });
    }

    @Test
    void chatModelFilter_shouldNotBeCreated_whenDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.trace-llm-calls=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ChatModelObservationFilter.class);
                });
    }

    // --- Umbrella tracing-enabled switch ---

    @Test
    void tracingDisabled_killsSpanConventions() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.tracing-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CONVENTIONS_BEAN);
                });
    }

    @Test
    void tracingDisabled_killsChatModelFilter_evenIfTraceLlmCallsTrue() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues(
                        "embabel.agent.platform.observability.tracing-enabled=false",
                        "embabel.agent.platform.observability.trace-llm-calls=true"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ChatModelObservationFilter.class);
                });
    }

    @Test
    void tracingDisabled_keepsMetricsListener() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, MeterRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.tracing-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbabelMetricsEventListener.class);
                });
    }

    // --- Properties binding ---

    @Test
    void propertiesBean_shouldBeCreated() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservabilityProperties.class);
                });
    }

    @Test
    void properties_shouldApplyCustomValues() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues(
                        "embabel.agent.platform.observability.service-name=my-app",
                        "embabel.agent.platform.observability.max-attribute-length=2000"
                )
                .run(context -> {
                    ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
                    assertThat(props.getServiceName()).isEqualTo("my-app");
                    assertThat(props.getMaxAttributeLength()).isEqualTo(2000);
                });
    }

    // --- MDC propagation ---

    @Test
    void mdcPropagationListener_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MdcPropagationEventListener.class);
                });
    }

    @Test
    void mdcPropagationListener_shouldNotBeCreated_whenDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.mdc-propagation=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MdcPropagationEventListener.class);
                });
    }

    // --- HTTP detail tracing ---

    @Test
    void httpBodyCachingFilter_shouldNotBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(HttpBodyCachingFilter.class);
                });
    }

    @Test
    void httpBodyCachingFilter_shouldBeCreated_whenTraceHttpDetailsEnabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.trace-http-details=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpBodyCachingFilter.class);
                });
    }

    @Test
    void httpRequestObservationFilter_shouldNotBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(HttpRequestObservationFilter.class);
                });
    }

    @Test
    void httpRequestObservationFilter_shouldBeCreated_whenTraceHttpDetailsEnabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.trace-http-details=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpRequestObservationFilter.class);
                });
    }

    // --- Metrics listener ---

    @Test
    void metricsListener_shouldBeCreated_whenMeterRegistryAvailable() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, MeterRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbabelMetricsEventListener.class);
                });
    }

    @Test
    void metricsListener_shouldNotBeCreated_whenMetricsDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, MeterRegistryConfig.class)
                .withPropertyValues("embabel.agent.platform.observability.metrics-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EmbabelMetricsEventListener.class);
                });
    }

    @Test
    void metricsListener_shouldNotBeCreated_whenNoMeterRegistry() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EmbabelMetricsEventListener.class);
                });
    }

    // --- Test configurations providing mock beans ---

    @Configuration
    static class ObservationRegistryConfig {
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
