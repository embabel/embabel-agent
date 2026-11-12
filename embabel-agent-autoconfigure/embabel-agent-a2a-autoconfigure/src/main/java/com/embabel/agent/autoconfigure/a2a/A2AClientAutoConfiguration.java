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
package com.embabel.agent.autoconfigure.a2a;

import com.embabel.agent.a2a.config.A2AConfigurationProperties;
import com.embabel.agent.a2a.client.A2AHttpClientFactory;
import com.embabel.agent.a2a.client.EmbabelA2AClient;
import com.embabel.agent.a2a.client.api.A2AClient;
import com.embabel.agent.a2a.client.spi.SpringRestClientA2AHttpClient;
import com.embabel.agent.api.common.Asyncer;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import io.a2a.client.http.JdkA2AHttpClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

/**
 * Auto-configures the A2A client: the {@link A2AClient} agents use to call remote A2A agents,
 * its HTTP transport, and the {@link RestClient} both run on.
 * <p>
 * Every bean backs off when the application defines its own: {@link A2AClient},
 * {@link A2AHttpClientFactory}, or a {@link RestClient} named {@value #A2A_REST_CLIENT}.
 * <p>
 * Registered here, not in the server's {@code A2AConfiguration}, so client-only apps
 * (no servlet web server) can use the A2A client: {@code A2AConfiguration} is
 * {@code @ConditionalOnWebApplication}.
 */
@AutoConfiguration
@EnableConfigurationProperties(A2AConfigurationProperties.class)
public class A2AClientAutoConfiguration {

    /**
     * Bean name and qualifier of the {@link RestClient} used for A2A transport and agent-card discovery.
     * <p>
     * To replace it, define a {@code RestClient} bean with this name. If the application also has
     * its own {@code RestClient} injected by type, declare the replacement with
     * {@code @Bean(name = "a2aRestClient", defaultCandidate = false)} as this class does,
     * so the two do not compete.
     */
    public static final String A2A_REST_CLIENT = "a2aRestClient";

    /**
     * The {@link RestClient} shared by the A2A HTTP transport and agent-card discovery.
     *
     * <p><b>Trace propagation.</b>
     * Uses Spring Boot's auto-configured {@code RestClient.Builder} when one exists. In Boot 4 that
     * bean comes from {@code spring-boot-restclient}, which apps often don't include, so the fallback
     * builder is given the {@link ObservationRegistry} directly. Either way each outbound call is
     * observed, so a tracing bridge (e.g. {@code micrometer-tracing-bridge-otel}) injects the W3C
     * {@code traceparent} header and the remote agent joins the caller's trace. Without a tracing
     * bridge no header is sent.
     *
     * <p><b>Timeouts.</b>
     * See {@link #a2aRequestFactory}: a connect timeout so an unreachable agent fails fast, and a
     * read timeout so a slow or hung remote agent does not block the caller indefinitely.
     * The request factory carrying them replaces any request factory set on Boot's
     * {@code RestClient.Builder}. To keep your own (for a proxy, TLS or a connection pool), define
     * your own {@value #A2A_REST_CLIENT} bean and set the timeouts there.
     *
     * <p><b>Not a default candidate.</b>
     * Registered with {@code defaultCandidate = false}: it is injected only where the
     * {@value #A2A_REST_CLIENT} qualifier asks for it. An application's own {@code RestClient} bean
     * therefore stays the only candidate for injection by type, and neither the application nor
     * the A2A beans fail with "expected single matching bean but found 2".
     */
    @Bean(name = A2A_REST_CLIENT, defaultCandidate = false)
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean(name = A2A_REST_CLIENT)
    public RestClient a2aRestClient(
            A2AConfigurationProperties properties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        ObservationRegistry registry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
        return restClientBuilderProvider
                .getIfAvailable(() -> RestClient.builder().observationRegistry(registry))
                .requestFactory(a2aRequestFactory(properties.getClient()))
                .build();
    }

    /**
     * The request factory for A2A calls, applying both client timeouts:
     * <ul>
     *   <li>connect: {@code embabel.agent.platform.a2a.client.connect-timeout-seconds}, default
     *       {@link A2AConfigurationProperties#DEFAULT_CLIENT_CONNECT_TIMEOUT_SECONDS}, so an unreachable
     *       agent fails fast;</li>
     *   <li>read: {@code embabel.agent.platform.a2a.client.timeout-seconds}, default
     *       {@link A2AConfigurationProperties#DEFAULT_CLIENT_TIMEOUT_SECONDS}. A non-streaming call returns
     *       only when the remote agent has finished its whole run, so this is effectively the maximum
     *       duration of a remote agent run.</li>
     * </ul>
     *
     * @param client the bound {@code embabel.agent.platform.a2a.client.*} settings
     * @return a new request factory with both timeouts set
     */
    static SimpleClientHttpRequestFactory a2aRequestFactory(A2AConfigurationProperties.Client client) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(client.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(client.getTimeoutSeconds()));
        return requestFactory;
    }

    /**
     * A2A HTTP client factory backed by Spring {@link RestClient}.
     * Uses the {@value #A2A_REST_CLIENT} bean, selected by qualifier, so the transport gets the
     * A2A timeout and trace propagation rather than an application's own {@code RestClient}.
     */
    @Bean
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean(A2AHttpClientFactory.class)
    public A2AHttpClientFactory springRestClientA2AHttpClientFactory(
            Asyncer asyncer,
            @Qualifier(A2A_REST_CLIENT) RestClient a2aRestClient) {
        return () -> new SpringRestClientA2AHttpClient(a2aRestClient, asyncer);
    }

    /**
     * Fallback when Spring Web is absent. No automatic OTel propagation —
     * relies on the OTel Java agent or manual header injection.
     */
    @Bean
    @ConditionalOnMissingBean(A2AHttpClientFactory.class)
    public A2AHttpClientFactory jdkA2AHttpClientFactory() {
        return JdkA2AHttpClient::new;
    }

    /**
     * The {@link A2AClient} agents use to call remote A2A agents.
     * <p>
     * Agent-card discovery uses the {@value #A2A_REST_CLIENT} bean, selected by qualifier, so it gets
     * the same timeout, trace propagation and TLS/proxy settings as the transport. Without the
     * qualifier, an application's own {@code RestClient} would be picked up here instead.
     * Falls back to a plain {@code RestClient} when no {@value #A2A_REST_CLIENT} bean exists.
     */
    @Bean
    @ConditionalOnMissingBean(A2AClient.class)
    public A2AClient embabelA2AClient(
            A2AHttpClientFactory httpClientFactory,
            EmbabelObjectMapperHolder objectMapperHolder,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            @Qualifier(A2A_REST_CLIENT) ObjectProvider<RestClient> a2aRestClientProvider) {
        // ObservationRegistry is the Micrometer hub that routes observations to metrics backends
        // (Prometheus, etc.) and tracing backends (Zipkin, OTLP, etc.) via registered handlers.
        // Falls back to NOOP when no backend is configured — observations compile away to nothing.
        ObservationRegistry registry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
        RestClient cardRestClient = a2aRestClientProvider.getIfAvailable(RestClient::create);
        return new EmbabelA2AClient(httpClientFactory, objectMapperHolder, registry, cardRestClient);
    }
}
