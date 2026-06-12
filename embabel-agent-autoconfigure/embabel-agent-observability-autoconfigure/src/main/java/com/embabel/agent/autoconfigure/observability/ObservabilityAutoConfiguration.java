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
import com.embabel.agent.observability.mdc.MdcPropagationEventListener;
import com.embabel.agent.observability.observation.ChatModelObservationFilter;
import com.embabel.agent.observability.observation.EmbabelActionObservationConvention;
import com.embabel.agent.observability.observation.EmbabelAgentObservationConvention;
import com.embabel.agent.observability.observation.EmbabelLlmObservationConvention;
import com.embabel.agent.observability.observation.EmbabelSpanEventListener;
import com.embabel.agent.observability.observation.EmbabelToolLoopObservationConvention;
import com.embabel.agent.observability.metrics.EmbabelMetricsEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Embabel Agent observability.
 *
 * <p>Configures tracing infrastructure using Spring Observation API.
 *
 * @see ObservabilityProperties
 * @since 0.3.4
 */
@AutoConfiguration(
        after = MicrometerTracingAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration"
        }
)
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnProperty(prefix = "embabel.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    /**
     * Registers the four global span conventions (agent, action, tool loop, LLM call) on the
     * ObservationRegistry. The core opens each observation with a thin context and a name only;
     * these conventions — matched by context type — supply all attributes. This replaces the
     * legacy event-listener span reconstruction with direct Micrometer instrumentation.
     *
     * @return a customizer registering the four conventions
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = {"tracing-enabled", "trace-agent-events"}, havingValue = "true", matchIfMissing = true)
    public ObservationRegistryCustomizer<ObservationRegistry> embabelSpanConventionsCustomizer(ObservabilityProperties properties) {
        log.info("Registering Embabel span conventions (agent, action, tool_loop, llm) for direct Micrometer instrumentation");
        int maxAttributeLength = properties.getMaxAttributeLength();
        return registry -> registry.observationConfig()
                .observationConvention(new EmbabelAgentObservationConvention(maxAttributeLength))
                .observationConvention(new EmbabelActionObservationConvention(maxAttributeLength))
                .observationConvention(new EmbabelToolLoopObservationConvention())
                .observationConvention(new EmbabelLlmObservationConvention());
    }

    /**
     * Disables span tiers by name without coupling the core to any flag, via an
     * {@link io.micrometer.observation.ObservationPredicate}:
     * <ul>
     *   <li>Spring AI's native {@code tool call} span is <em>always</em> dropped — Embabel emits its
     *       own richer {@code embabel.tool} point span (correlation id, tool group, status, error,
     *       duration) from {@code ToolCallResponseEvent}, gated by {@code trace-tool-calls}. Keeping
     *       both would double every tool span.</li>
     *   <li>the core-produced {@code embabel.tool_loop} span is dropped when
     *       {@code trace-tool-loop=false}.</li>
     * </ul>
     * A suppressed observation becomes a no-op, so its children simply re-parent to the next live
     * ancestor.
     *
     * @param properties the observability properties (read at observation time)
     * @return a customizer registering the tier filter
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = "tracing-enabled", havingValue = "true", matchIfMissing = true)
    public ObservationRegistryCustomizer<ObservationRegistry> embabelTierFilterCustomizer(ObservabilityProperties properties) {
        return registry -> registry.observationConfig().observationPredicate((name, context) -> {
            if ("tool call".equals(name)) {
                return false;
            }
            if (!properties.isTraceToolLoop() && "embabel.tool_loop".equals(name)) {
                return false;
            }
            return true;
        });
    }

    /**
     * Event-driven listener that emits instantaneous spans for point events (LLM and embedding
     * invocations: model, token usage, cost; plus planning, RAG, ranking, state transitions and
     * lifecycle states) as children of the current observation. These events hold no scope, so a
     * listener is safe (no leaks). Each type is gated by its own {@code trace-*} switch inside the
     * listener.
     *
     * @param observationRegistry the ObservationRegistry the spans are emitted on
     * @param properties the observability properties
     * @return the span-emitting event listener
     */
    @Bean
    @ConditionalOnBean(ObservationRegistry.class)
    @ConditionalOnProperty(prefix = "embabel.observability", name = "tracing-enabled", havingValue = "true", matchIfMissing = true)
    public EmbabelSpanEventListener embabelSpanEventListener(
            ObservationRegistry observationRegistry,
            ObservabilityProperties properties) {
        log.info("Configuring Embabel point-event span listener (LLM + embedding invocations)");
        return new EmbabelSpanEventListener(observationRegistry, properties);
    }

    /**
     * Servlet-dependent beans isolated in a nested configuration class.
     *
     * <p>This prevents {@code NoClassDefFoundError: jakarta/servlet/Filter} in non-web
     * applications (e.g., shell apps). Without this isolation, Spring's
     * {@code @ConditionalOnMissingBean} bean type deduction reflectively loads all methods
     * on the outer class, triggering class loading of {@link HttpBodyCachingFilter}
     * (which extends {@code OncePerRequestFilter} → {@code jakarta.servlet.Filter})
     * <em>before</em> any per-method {@code @ConditionalOnClass} guards can run.
     *
     * @see <a href="https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html#features.developing-auto-configuration.condition-annotations.class-conditions">Spring Boot: Class Conditions</a>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnProperty(prefix = "embabel.observability", name = "trace-http-details", havingValue = "true")
    static class ServletObservabilityConfiguration {

        private static final Logger log = LoggerFactory.getLogger(ServletObservabilityConfiguration.class);

        /**
         * Creates a servlet filter that wraps request/response for body caching.
         *
         * @return the body caching filter
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "org.springframework.web.util.ContentCachingRequestWrapper")
        public HttpBodyCachingFilter httpBodyCachingFilter() {
            log.debug("Configuring HTTP body caching filter for request/response tracing");
            return new HttpBodyCachingFilter();
        }

        /**
         * Creates observation filter to enrich HTTP server observations with request/response details.
         *
         * @param properties the observability properties
         * @return the HTTP request observation filter
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "org.springframework.http.server.observation.ServerRequestObservationContext")
        public HttpRequestObservationFilter httpRequestObservationFilter(ObservabilityProperties properties) {
            log.debug("Configuring HTTP request observation filter for request/response tracing");
            return new HttpRequestObservationFilter(properties.getMaxAttributeLength());
        }
    }

    /**
     * Creates filter to enrich Spring AI LLM observations with prompt/completion.
     *
     * @param properties the observability properties
     * @return the configured observation filter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.ai.chat.observation.ChatModelObservationContext")
    @ConditionalOnProperty(prefix = "embabel.observability", name = {"tracing-enabled", "trace-llm-calls"}, havingValue = "true", matchIfMissing = true)
    public ChatModelObservationFilter chatModelObservationFilter(ObservabilityProperties properties) {
        log.debug("Configuring ChatModel observation filter for LLM call tracing");
        return new ChatModelObservationFilter(properties.getMaxAttributeLength());
    }

    /**
     * Creates the MDC propagation listener for log correlation.
     *
     * @param properties the observability properties
     * @return the MDC propagation event listener
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = "mdc-propagation", havingValue = "true", matchIfMissing = true)
    public MdcPropagationEventListener mdcPropagationEventListener(ObservabilityProperties properties) {
        log.info("Configuring Embabel Agent MDC propagation for log correlation");
        return new MdcPropagationEventListener(properties);
    }

    /**
     * Creates the Micrometer business metrics listener.
     *
     * @param meterRegistry the meter registry
     * @param properties the observability properties
     * @return the metrics event listener
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "embabel.observability", name = "metrics-enabled",
            havingValue = "true", matchIfMissing = true)
    public EmbabelMetricsEventListener embabelMetricsEventListener(
            MeterRegistry meterRegistry, ObservabilityProperties properties) {
        log.info("Configuring Embabel Agent Micrometer metrics listener");
        return new EmbabelMetricsEventListener(meterRegistry, properties);
    }

}
