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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.decision.LlmDecisionRetryProperties
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory
import com.embabel.common.ai.model.ModelProvider
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Supplies the `LlmDecisionServiceFactory` bean, with retry settings bound from
 * `embabel.agent.platform.decisions.llm`.
 *
 * ```yaml
 * embabel:
 *   agent:
 *     platform:
 *       decisions:
 *         llm:
 *           max-attempts: 5
 * ```
 *
 * Services declared under `embabel.agent.platform.decisions.llm.services` are registered by the
 * platform autoconfiguration, which builds them with this factory.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmDecisionRetryProperties::class)
internal class LlmDecisionServiceConfiguration {

    /**
     * The factory applications inject. Configured services are built by this bean too, so an
     * application that supplies its own factory changes them as well.
     */
    @Bean
    @ConditionalOnMissingBean
    fun llmDecisionServiceFactory(
        llmOperations: LlmOperations,
        modelProvider: ModelProvider,
        observationRegistry: ObjectProvider<ObservationRegistry>,
        retry: LlmDecisionRetryProperties,
    ): LlmDecisionServiceFactory = LlmDecisionServiceFactory(
        llmOperations = llmOperations,
        modelProvider = modelProvider,
        observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
        retry = retry,
    )
}
