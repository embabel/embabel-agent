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
import com.embabel.common.util.EmbabelObjectMapperHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
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
            .withBean(EmbabelObjectMapperHolder.class, EmbabelObjectMapperHolder::createDefault);

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
}
