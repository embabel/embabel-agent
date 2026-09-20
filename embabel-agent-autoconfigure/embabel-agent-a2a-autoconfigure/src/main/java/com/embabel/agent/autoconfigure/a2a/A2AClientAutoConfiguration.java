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
import com.embabel.agent.a2a.client.spi.springai.SpringRestClientA2AHttpClient;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import io.a2a.client.http.JdkA2AHttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
public class A2AClientAutoConfiguration {

    /**
     * OTel-instrumented factory: the OTel Java agent instruments RestClient at runtime,
     * propagating W3C traceparent headers automatically.
     */
    @Bean
    @ConditionalOnClass(RestClient.class)
    public A2AHttpClientFactory springRestClientA2AHttpClientFactory() {
        return () -> new SpringRestClientA2AHttpClient(RestClient.create());
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
            A2AConfigurationProperties properties) {
        return new EmbabelA2AClient(httpClientFactory, objectMapperHolder, properties.getClient().getTimeoutSeconds());
    }
}
