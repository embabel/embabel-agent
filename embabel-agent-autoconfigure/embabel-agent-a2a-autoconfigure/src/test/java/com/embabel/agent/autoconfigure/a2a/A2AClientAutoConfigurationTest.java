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

import com.embabel.agent.a2a.client.A2AHttpClientFactory;
import com.embabel.agent.a2a.client.api.A2AClient;
import com.embabel.agent.a2a.config.A2AConfigurationProperties;
import com.embabel.agent.api.common.Asyncer;
import com.embabel.agent.spi.support.ExecutorAsyncer;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link A2AClientAutoConfiguration} registers the expected beans and
 * respects {@code @ConditionalOnMissingBean} guards.
 */
class A2AClientAutoConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(A2AConfigurationProperties.class)
    static class PropertiesConfiguration {}

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(A2AClientAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(EmbabelObjectMapperHolder.class, EmbabelObjectMapperHolder::createDefault)
            .withBean(Asyncer.class, () -> new ExecutorAsyncer(Runnable::run));

    @Test
    void registersA2AHttpClientFactoryAndClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(A2AHttpClientFactory.class);
            assertThat(context).hasSingleBean(A2AClient.class);
        });
    }

    @Test
    void doesNotReplaceUserDefinedA2AClient() {
        contextRunner
                .withBean(A2AClient.class, () -> mock(A2AClient.class))
                .run(context -> assertThat(context).hasSingleBean(A2AClient.class));
    }

    @Test
    void doesNotReplaceUserDefinedA2AHttpClientFactory() {
        contextRunner
                .withBean(A2AHttpClientFactory.class, () -> mock(A2AHttpClientFactory.class))
                .run(context -> assertThat(context).hasSingleBean(A2AHttpClientFactory.class));
    }

    @Nested
    class A2ARestClientBean {

        @Test
        void registersA2ARestClient() {
            contextRunner.run(context ->
                assertThat(context).hasBean("a2aRestClient")
            );
        }

        @Test
        void doesNotReplaceUserDefinedA2ARestClient() {
            contextRunner
                    .withBean("a2aRestClient", RestClient.class, RestClient::create)
                    .run(context -> assertThat(context).hasBean("a2aRestClient"));
        }
    }

    /**
     * The A2A RestClient applies both client timeouts: connect, so an unreachable host fails fast,
     * and read, so a hung remote agent does not block the caller indefinitely.
     */
    @Nested
    class Timeouts {

        @Test
        void requestFactoryAppliesConnectAndReadTimeouts() {
            A2AConfigurationProperties.Client client = new A2AConfigurationProperties.Client(120, 3);

            SimpleClientHttpRequestFactory factory = A2AClientAutoConfiguration.a2aRequestFactory(client);

            assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(3_000);
            assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(120_000);
        }

        @Test
        void callFailsWhenRemoteAgentDoesNotAnswerWithinReadTimeout() throws IOException {
            HttpServer slowServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            slowServer.createContext("/", exchange -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            slowServer.start();
            try {
                contextRunner
                        .withPropertyValues("embabel.agent.platform.a2a.client.timeout-seconds=1")
                        .run(context -> {
                            RestClient a2aRestClient = context.getBean(A2AClientAutoConfiguration.A2A_REST_CLIENT, RestClient.class);
                            String url = "http://localhost:" + slowServer.getAddress().getPort() + "/";

                            assertThatThrownBy(() -> a2aRestClient.get().uri(url).retrieve().toBodilessEntity())
                                    .isInstanceOf(ResourceAccessException.class)
                                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
                        });
            } finally {
                slowServer.stop(0);
            }
        }
    }

    /**
     * Apps often define their own {@link RestClient} bean and inject it by type. The A2A RestClient
     * must not compete with it: the app keeps receiving its own bean, and the A2A beans keep
     * receiving {@code a2aRestClient}.
     */
    @Nested
    class AppDefinedRestClient {

        record AppService(RestClient restClient) {}

        @Configuration
        static class AppConfiguration {

            @Bean
            RestClient appRestClient() {
                return RestClient.create();
            }

            @Bean
            AppService appService(RestClient restClient) {
                return new AppService(restClient);
            }
        }

        private final ApplicationContextRunner appContextRunner =
                contextRunner.withUserConfiguration(AppConfiguration.class);

        @Test
        void appStartsAndKeepsInjectingItsOwnRestClient() {
            appContextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AppService.class).restClient())
                        .isSameAs(context.getBean("appRestClient"));
            });
        }

        @Test
        void a2aClientUsesA2ARestClientForCardDiscovery() {
            appContextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                Object cardRestClient = ReflectionTestUtils.getField(context.getBean(A2AClient.class), "cardRestClient");
                assertThat(cardRestClient).isSameAs(context.getBean("a2aRestClient"));
            });
        }

        @Test
        void a2aTransportUsesA2ARestClient() {
            appContextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                Object transportRestClient = ReflectionTestUtils.getField(
                        context.getBean(A2AHttpClientFactory.class).create(), "restClient");
                assertThat(transportRestClient).isSameAs(context.getBean("a2aRestClient"));
            });
        }
    }

    /**
     * Without Boot's {@code RestClient.Builder} bean (absent unless the app adds spring-boot-restclient),
     * the A2A RestClient must still carry the ObservationRegistry so the called agent receives a W3C
     * {@code traceparent} header and joins the caller's trace.
     */
    @Nested
    class TraceparentPropagation {

        /**
         * A well-formed W3C Trace Context {@code traceparent} header:
         * {@code version-traceId-parentId-traceFlags}, lowercase hex, dash-separated.
         * <ul>
         *   <li>{@code version}: {@code 00}, the only version currently defined</li>
         *   <li>{@code traceId}: 32 hex digits (16 bytes), shared by every agent in the chain</li>
         *   <li>{@code parentId}: 16 hex digits (8 bytes), the caller's client span</li>
         *   <li>{@code traceFlags}: 2 hex digits (1 byte). Any value is accepted: bit 0 is "sampled",
         *       and OTel SDKs implementing Trace Context level 2 also set bit 1 ("random trace id"),
         *       giving {@code 03}.</li>
         * </ul>
         *
         * @see <a href="https://www.w3.org/TR/trace-context/#traceparent-header">W3C Trace Context: traceparent</a>
         */
        private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

        private HttpServer server;
        private final AtomicReference<String> receivedTraceparent = new AtomicReference<>();

        @BeforeEach
        void startServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                receivedTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
        }

        @AfterEach
        void stopServer() {
            server.stop(0);
        }

        @Test
        void sendsTraceparentWithoutBootRestClientBuilder() {
            contextRunner
                    .withBean(ObservationRegistry.class, TraceparentPropagation::tracingObservationRegistry)
                    .run(context -> {
                        RestClient a2aRestClient = context.getBean("a2aRestClient", RestClient.class);

                        a2aRestClient.get()
                                .uri("http://localhost:" + server.getAddress().getPort() + "/")
                                .retrieve()
                                .toBodilessEntity();

                        assertThat(receivedTraceparent.get()).matches(TRACEPARENT);
                    });
        }

        private static ObservationRegistry tracingObservationRegistry() {
            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(SdkTracerProvider.builder().build())
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
            OtelTracer tracer = new OtelTracer(
                    openTelemetry.getTracer("test"),
                    currentTraceContext,
                    event -> {
                    },
                    new OtelBaggageManager(currentTraceContext, List.of(), List.of()));
            OtelPropagator propagator = new OtelPropagator(openTelemetry.getPropagators(), openTelemetry.getTracer("test"));

            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(
                    new ObservationHandler.FirstMatchingCompositeObservationHandler(
                            new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                            new DefaultTracingObservationHandler(tracer)));
            return registry;
        }
    }
}
