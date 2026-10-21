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
package com.embabel.agent.config.models.byok

import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.CredentialModelFactory
import com.embabel.common.ai.model.EmbeddingService
import org.springframework.context.ApplicationContext

/** The chat credential factories in [this] context, by bean name. */
internal fun ApplicationContext.chatModelFactories(): Map<String, CredentialModelFactory<LlmService<*>>> =
    @Suppress("UNCHECKED_CAST")
    (getBeansOfType(CredentialModelFactory::class.java).filterValues { it.kind === LlmService.KIND }
        as Map<String, CredentialModelFactory<LlmService<*>>>)

/** The embedding credential factories in [this] context, by bean name. */
internal fun ApplicationContext.embeddingModelFactories(): Map<String, CredentialModelFactory<EmbeddingService>> =
    @Suppress("UNCHECKED_CAST")
    (getBeansOfType(CredentialModelFactory::class.java).filterValues { it.kind === EmbeddingService.KIND }
        as Map<String, CredentialModelFactory<EmbeddingService>>)
