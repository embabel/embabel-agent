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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the {@link LlmDecisionServiceFactory} bean, with retry settings bound from
 * {@code embabel.agent.platform.decisions.llm}. The factory is a decision model provider for every
 * chat model, so a decision default or role can name a chat model and the registry builds its
 * prompted service on first use.
 *
 * <pre>{@code
 * embabel:
 *   agent:
 *     platform:
 *       decisions:
 *         llm:
 *           max-attempts: 5
 *   models:
 *     decision:
 *       roles:
 *         triage: small-chat-model
 * }</pre>
 */
@AutoConfiguration(after = AgentPlatformAutoConfiguration.class)
@EnableConfigurationProperties(LlmDecisionRetryProperties.class)
public class LlmDecisionServicesAutoConfiguration {

    /** An application factory bean replaces this one, including as the chat model provider. */
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
}
