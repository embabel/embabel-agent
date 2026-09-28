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
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Registers a decision service bean for each entry under
 * {@code embabel.agent.platform.decisions.llm.services}, named after the entry's key.
 *
 * <pre>{@code
 * embabel:
 *   agent:
 *     platform:
 *       decisions:
 *         llm:
 *           services:
 *             triage:
 *               llm: small-chat-model
 * }</pre>
 */
@AutoConfiguration(after = AgentPlatformAutoConfiguration.class)
public class LlmDecisionServicesAutoConfiguration {

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
