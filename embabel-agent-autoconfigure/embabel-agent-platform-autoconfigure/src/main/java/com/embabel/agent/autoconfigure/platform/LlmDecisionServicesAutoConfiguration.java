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
package com.embabel.agent.autoconfigure.platform;

import com.embabel.agent.core.internal.LlmOperations;
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.common.ai.model.ModelProvider;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Supplies the {@link LlmDecisionServiceFactory} bean applications inject, with retry settings bound
 * from {@code embabel.agent.platform.decisions.llm}, and registers a decision service bean for each
 * entry under {@code embabel.agent.platform.decisions.llm.services}, named after the entry's key.
 *
 * <pre>{@code
 * embabel:
 *   agent:
 *     platform:
 *       decisions:
 *         llm:
 *           max-attempts: 5
 *           services:
 *             triage:
 *               llm: small-chat-model
 * }</pre>
 */
@AutoConfiguration(after = AgentPlatformAutoConfiguration.class)
@EnableConfigurationProperties(LlmDecisionRetryProperties.class)
public class LlmDecisionServicesAutoConfiguration {

    /**
     * Configured services are built by this bean too, so an application that supplies its own
     * factory changes them as well.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({LlmOperations.class, ModelProvider.class})
    public LlmDecisionServiceFactory llmDecisionServiceFactory(
            LlmOperations llmOperations,
            ModelProvider modelProvider,
            ObjectProvider<ObservationRegistry> observationRegistry,
            LlmDecisionRetryProperties retry) {
        return new LlmDecisionServiceFactory(
                llmOperations, modelProvider, retry, observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
    }

    /**
     * Static, so Spring creates it before any ordinary bean and the service definitions exist
     * before anything that injects them by name.
     */
    @Bean
    public static LlmDecisionServicesRegistrar llmDecisionServicesRegistrar(
            Environment environment, BeanFactory beanFactory) {
        return new LlmDecisionServicesRegistrar(LlmDecisionServicesRegistrar.bindServices(environment), beanFactory);
    }
}
