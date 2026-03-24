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
package com.embabel.common.ai.autoconfig

import java.time.Instant

/**
 * Result of model provider initialization.
 *
 * Each provider auto-configuration returns a [ProviderInitialization] bean from its
 * `@Bean` factory method. Inside that factory method, individual [LlmService] and
 * [EmbeddingService][com.embabel.common.ai.model.EmbeddingService] instances are
 * registered via [ConfigurableBeanFactory.registerSingleton][org.springframework.beans.factory.config.ConfigurableBeanFactory.registerSingleton],
 * using the model name as the bean name. This allows dynamic registration of
 * multiple models per provider, with names driven by configuration rather than
 * compile-time `@Bean` definitions.
 *
 * **Note on direct consumption:** Beans registered via `registerSingleton` are not
 * visible to Spring's dependency resolver at bean-definition time. This is transparent
 * in most cases, since framework beans like [ModelProvider] already depend on the
 * initializer beans and handle resolution internally. However, if application code
 * outside the framework injects an [EmbeddingService] or [LlmService] directly
 * (e.g. to wire a custom store), Spring may attempt to resolve the dependency before
 * the initializer's factory method has run — resulting in a `NoSuchBeanDefinitionException`
 * even though the bean would have been registered moments later. In this case, add
 * `@DependsOn` on the corresponding initializer bean
 * (e.g. `@DependsOn("onnxEmbeddingInitializer")`) to force ordering.
 */
data class ProviderInitialization(
    val provider: String,
    val registeredLlms: List<RegisteredModel>,
    val registeredEmbeddings: List<RegisteredModel> = emptyList(),
    val initializedAt: Instant = Instant.now()
) {
    val totalLlms: Int get() = registeredLlms.size
    val totalEmbeddings: Int get() = registeredEmbeddings.size

    fun summary(): String =
        "$provider: Initialized $totalLlms LLM(s) and $totalEmbeddings embedding(s)"
}

/**
 * Represents a registered model with its bean name and model ID.
 */
data class RegisteredModel(
    val beanName: String,
    val modelId: String
)
