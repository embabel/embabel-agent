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

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Offers a prompted decision and classification service over the platform's default LLM as the
 * family default. It is off unless {@code embabel.agent.platform.decisions.llm.default-candidate}
 * is {@code true}.
 *
 * <p>When it is on, it registers {@code llmDefaultDecisionService} over the default LLM and offers it
 * as the decision and classification family default, but only when no other decision or
 * classification service and no default candidate is defined. An application service bean, a
 * configured prompted or TypeSafe service, or another default candidate leaves everything as it was.
 *
 * <p>The default LLM is resolved once, at startup. When no chat model is configured yet the service
 * is built over the placeholder model and a WARN says so; it does not pick up a model that arrives
 * later. A model supplied per user can still be used by passing a service to {@code using(service)}.
 *
 * <p>Turn it on with:
 *
 * <pre>{@code
 * embabel:
 *   agent:
 *     platform:
 *       decisions:
 *         llm:
 *           default-candidate: true
 * }</pre>
 */
@AutoConfiguration(
        after = LlmDecisionServicesAutoConfiguration.class,
        afterName = "com.embabel.agent.autoconfigure.models.typesafe.AgentTypeSafeAutoConfiguration",
        before = DecisionServiceRegistryAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "embabel.agent.platform.decisions.llm",
        name = "default-candidate",
        havingValue = "true",
        matchIfMissing = false)
public class LlmDecisionDefaultCandidateAutoConfiguration {

    /**
     * Static, so Spring creates it before any ordinary bean, and registered after the configured
     * service registrars so it can see what they define.
     */
    @Bean
    public static LlmDecisionDefaultCandidateRegistrar llmDecisionDefaultCandidateRegistrar(BeanFactory beanFactory) {
        return new LlmDecisionDefaultCandidateRegistrar(beanFactory);
    }
}
