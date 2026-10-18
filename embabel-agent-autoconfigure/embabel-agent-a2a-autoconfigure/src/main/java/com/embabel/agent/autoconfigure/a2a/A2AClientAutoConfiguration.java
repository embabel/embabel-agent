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
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;

@AutoConfiguration
// Register here so client-only apps (no servlet web server) can use the A2A client
// without depending on A2AConfiguration, which is server-only (@ConditionalOnWebApplication).
@EnableConfigurationProperties(A2AConfigurationProperties.class)
public class A2AClientAutoConfiguration {

    /**
     * A2A HTTP client factory backed by Spring RestClient.
     * Uses Spring Boot's auto-configured RestClient.Builder when available — Boot's builder
     * carries OTel instrumentation that propagates W3C traceparent headers automatically,
     * connecting the caller's trace to the remote agent's. Falls back to a plain builder
     * when Boot's builder is absent (e.g. in tests or non-Boot contexts).
     * Read timeout is applied in both cases so a slow or hung remote agent does not
     * block the caller indefinitely.
     */
    @Bean
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnMissingBean(A2AHttpClientFactory.class)
    public A2AHttpClientFactory springRestClientA2AHttpClientFactory(
            Asyncer asyncer,
            A2AConfigurationProperties properties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getClient().getTimeoutSeconds()));
        RestClient restClient = restClientBuilderProvider
                .getIfAvailable(RestClient::builder)
                .requestFactory(requestFactory)
                .build();
        return () -> new SpringRestClientA2AHttpClient(restClient, asyncer);
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

    @Bean
    @ConditionalOnMissingBean(A2AClient.class)
    public A2AClient embabelA2AClient(
            A2AHttpClientFactory httpClientFactory,
            EmbabelObjectMapperHolder objectMapperHolder,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        // ObservationRegistry is the Micrometer hub that routes observations to metrics backends
        // (Prometheus, etc.) and tracing backends (Zipkin, OTLP, etc.) via registered handlers.
        // Falls back to NOOP when no backend is configured — observations compile away to nothing.
        ObservationRegistry registry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new EmbabelA2AClient(httpClientFactory, objectMapperHolder, registry);
    }
}
