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
package com.embabel.agent.anthropic

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.ModelCapability
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelPlatform
import com.embabel.common.ai.model.ProviderCredential
import org.jetbrains.annotations.ApiStatus

/**
 * Anthropic: chat models only. Anthropic has no embeddings API, so an Anthropic key asked for an
 * embedding model gets none from this platform.
 *
 * The platform builds a model only for a credential whose provider is [AnthropicModels.PROVIDER],
 * compared ignoring case and surrounding spaces.
 *
 * ```kotlin
 * val platform = AnthropicPlatform()
 * val chat = platform.model(ProviderCredential(AnthropicModels.PROVIDER, key), LlmService.KIND, AnthropicModels.CLAUDE_HAIKU_4_5)
 * ```
 *
 * A model built here is billed to the user's key, so its pricing model is
 * [com.embabel.common.ai.model.PricingModel.ALL_YOU_CAN_EAT].
 */
@ApiStatus.Experimental
class AnthropicPlatform : ModelPlatform {

    override val kinds: Set<ModelKind<*, *>> = setOf(LlmService.KIND)

    override fun <C : ModelCapability> model(
        credential: ProviderCredential,
        kind: ModelKind<C, *>,
        model: String,
    ): C? {
        // Chat is the only kind this platform lists, so any other kind gets null.
        if (kind != LlmService.KIND) return null
        // Another provider's key is left to the platform for that provider.
        if (!credential.provider.trim().equals(AnthropicModels.PROVIDER, ignoreCase = true)) return null
        return kind.capability.cast(modelAt(ANTHROPIC, credential.apiKey, model))
    }

    override fun toString(): String = "AnthropicPlatform(kinds=chat)"

    companion object {

        /** Anthropic's own servers: a null base URL means the SDK default. */
        private val ANTHROPIC = CredentialEndpoint.Anthropic(provider = AnthropicModels.PROVIDER)

        /**
         * A chat model at [endpoint], built with [apiKey]. It does not check the provider.
         *
         * [AnthropicPlatform] builds through this function, and so does `embabel-agent-starter-byok`
         * for an Anthropic endpoint a `CredentialEndpointResolver` returns.
         */
        @ApiStatus.Internal
        @JvmStatic
        fun modelAt(endpoint: CredentialEndpoint.Anthropic, apiKey: String, model: String): LlmService<*> =
            AnthropicModelFactory(apiKey = apiKey, baseUrl = endpoint.baseUrl)
                .build(
                    model = model,
                    provider = endpoint.provider,
                    pricingModel = endpoint.pricingModel,
                    knowledgeCutoffDate = endpoint.knowledgeCutoffDate,
                )
    }
}
