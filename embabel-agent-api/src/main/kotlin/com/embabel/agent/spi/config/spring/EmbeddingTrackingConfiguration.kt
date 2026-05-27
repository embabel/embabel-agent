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

import com.embabel.agent.api.event.EmbeddingEventListener
import com.embabel.agent.spi.support.embedding.EmbeddingOperations
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.TokenCountEstimator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration that automatically wraps every [EmbeddingService] bean in
 * [EmbeddingOperations] so that embedding usage is tracked and priced against the
 * current [com.embabel.agent.core.AgentProcess].
 *
 * The wrapping happens via a [BeanPostProcessor], which intercepts both statically
 * declared `@Bean` methods and dynamically registered singletons (used by the model
 * autoconfigure modules — see e.g. `OpenAiModelsConfig`).
 *
 * Wrapping is idempotent: an already-wrapped [EmbeddingOperations] is returned as is.
 *
 * Every [EmbeddingEventListener] bean in the context is aggregated and injected into
 * each wrapped [EmbeddingOperations]. This is the channel through which standalone
 * (non-agent) callers receive embedding events.
 *
 * If no [TokenCountEstimator] is present in the context, the heuristic
 * (~ 4 chars per token) is used as a fallback.
 */
@Configuration
class EmbeddingTrackingConfiguration {

    @Bean
    fun embeddingTrackingBeanPostProcessor(
        tokenCountEstimatorProvider: ObjectProvider<TokenCountEstimator<String>>,
        embeddingEventListenersProvider: ObjectProvider<EmbeddingEventListener>,
    ): BeanPostProcessor = object : BeanPostProcessor {

        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is EmbeddingOperations) return bean
            if (bean is EmbeddingService) {
                val estimator = tokenCountEstimatorProvider.getIfAvailable {
                    TokenCountEstimator.heuristic()
                }
                val listener = embeddingEventListenersProvider
                    .orderedStream()
                    .reduce(EmbeddingEventListener.NOOP) { a, b -> a + b }
                return EmbeddingOperations(bean, estimator, listener)
            }
            return bean
        }
    }
}
