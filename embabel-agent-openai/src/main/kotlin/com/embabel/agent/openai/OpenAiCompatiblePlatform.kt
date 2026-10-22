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
package com.embabel.agent.openai

import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelCapability
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelPlatform
import com.embabel.common.ai.model.ProviderCredential
import org.jetbrains.annotations.ApiStatus

/**
 * The models that one key opens on a server that speaks the OpenAI wire protocol.
 *
 * Use this for a gateway or a self-hosted server. For OpenAI, DeepSeek and Mistral AI, use
 * [OpenAiPlatform], [DeepSeekPlatform] and [MistralAiPlatform], which already know the base URL
 * and the kinds that provider offers.
 *
 * The platform builds a model only for a credential whose provider is [provider]. It compares the
 * names ignoring case and surrounding spaces. A built model reports [provider], spelled as given
 * here, whatever spelling the credential used.
 *
 * Chat models are built without a test call. Embedding models are built with one test call, which
 * reads the width of the vectors the server returns, so the width a model reports is the width
 * the server produced.
 *
 * Kotlin:
 *
 * ```kotlin
 * @Bean
 * fun ourGatewayPlatform(): ModelPlatform =
 *     OpenAiCompatiblePlatform(provider = "OurGateway", baseUrl = "https://gateway.example.com/v1")
 * ```
 *
 * Java, for a server that has no embeddings endpoint:
 *
 * ```java
 * @Bean
 * ModelPlatform ourGatewayPlatform() {
 *     return new OpenAiCompatiblePlatform("OurGateway", "https://gateway.example.com/v1", OpenAiCompatiblePlatform.CHAT_ONLY);
 * }
 * ```
 *
 * A model built here is billed to the user's key, so its pricing model is
 * [com.embabel.common.ai.model.PricingModel.ALL_YOU_CAN_EAT].
 *
 * @param provider the provider name a credential must carry, for example "OurGateway". Surrounding
 * spaces are removed, so " OurGateway " is stored as "OurGateway". It must not be blank.
 * @param baseUrl the server's base URL, for example "https://gateway.example.com/v1". Null means
 * OpenAI's own servers, so set it for every other server.
 * @param kinds the kinds this server offers: [LlmService.KIND], [EmbeddingService.KIND] or both.
 * Defaults to both.
 * @param timeouts connect and read timeouts for every client this platform builds
 */
@ApiStatus.Experimental
open class OpenAiCompatiblePlatform @JvmOverloads constructor(
    provider: String,
    val baseUrl: String?,
    final override val kinds: Set<ModelKind<*, *>> = CHAT_AND_EMBEDDING,
    val timeouts: OpenAiClientTimeouts = OpenAiClientTimeouts.DEFAULT,
) : ModelPlatform {

    /**
     * The provider name, without surrounding spaces. [serves] trims the credential's provider too,
     * so " OurGateway " given here still matches a credential for "OurGateway".
     */
    val provider: String = provider.trim()

    init {
        // A blank name would match only a credential with a blank provider, so it fails here instead.
        require(this.provider.isNotEmpty()) { "OpenAiCompatiblePlatform needs a provider name; was given '$provider'" }
        // A kind this class has no client for would be listed and then never built, so it fails here instead.
        require(kinds.isNotEmpty() && CHAT_AND_EMBEDDING.containsAll(kinds)) {
            "OpenAiCompatiblePlatform builds only ${CHAT_AND_EMBEDDING.joinToString { it.name }}; was given $kinds"
        }
    }

    private val endpoint = CredentialEndpoint.OpenAiCompatible(provider = this.provider, baseUrl = baseUrl)

    /**
     * The same platform with other timeouts.
     *
     * ```kotlin
     * val patient = DeepSeekPlatform().withTimeouts(OpenAiClientTimeouts(read = Duration.ofMinutes(5)))
     * ```
     */
    open fun withTimeouts(timeouts: OpenAiClientTimeouts): OpenAiCompatiblePlatform =
        OpenAiCompatiblePlatform(provider, baseUrl, kinds, timeouts)

    override fun <C : ModelCapability> model(
        credential: ProviderCredential,
        kind: ModelKind<C, *>,
        model: String,
    ): C? {
        // A kind this platform does not list is never built. For example, DeepSeekPlatform lists
        // chat only, so it returns null for an embedding model.
        if (kind !in kinds) return null
        // Another provider's key is left to the platform for that provider.
        if (!serves(credential)) return null
        return modelAt(endpoint, credential.apiKey, kind, model, timeouts)
    }

    /**
     * Whether [credential] is a key for [provider]. The names are compared ignoring case and
     * surrounding spaces, so "  deepseek " matches "DeepSeek".
     */
    fun serves(credential: ProviderCredential): Boolean =
        credential.provider.trim().equals(provider, ignoreCase = true)

    // A null base URL means the client's default, which is OpenAI's own servers.
    override fun toString(): String =
        "${javaClass.simpleName}(provider=$provider, baseUrl=${baseUrl ?: "OpenAI default"}, kinds=${kinds.joinToString { it.name }})"

    companion object {

        /** Chat and embedding: the two kinds an OpenAI-compatible server can offer through this class. */
        @JvmField
        val CHAT_AND_EMBEDDING: Set<ModelKind<*, *>> = setOf(LlmService.KIND, EmbeddingService.KIND)

        /** Chat only, for a server without an embeddings endpoint. */
        @JvmField
        val CHAT_ONLY: Set<ModelKind<*, *>> = setOf(LlmService.KIND)

        /**
         * A model of [kind] at [endpoint], built with [apiKey]. It does not check the provider.
         *
         * Every OpenAI-compatible platform builds through this function, including the ones that
         * `embabel-agent-starter-byok` builds from a `CredentialEndpointResolver` answer.
         *
         * @return the model, or null if [kind] is neither chat nor embedding
         */
        @ApiStatus.Internal
        @JvmStatic
        fun <C : ModelCapability> modelAt(
            endpoint: CredentialEndpoint.OpenAiCompatible,
            apiKey: String,
            kind: ModelKind<C, *>,
            model: String,
            timeouts: OpenAiClientTimeouts,
        ): C? {
            val factory = OpenAiCompatibleModelFactory(baseUrl = endpoint.baseUrl, apiKey = apiKey, timeouts = timeouts)
            val built: ModelCapability? = when {
                // Chat: no test call. A wrong model fails the call that uses it and nothing else.
                kind == LlmService.KIND -> factory.openAiCompatibleLlm(
                    model = model,
                    pricingModel = endpoint.pricingModel,
                    provider = endpoint.provider,
                    knowledgeCutoffDate = endpoint.knowledgeCutoffDate,
                )
                // Embedding: one test call, so the width is read from the server's answer.
                // A vector index is created at that width, so a guessed width would break it.
                kind == EmbeddingService.KIND -> factory.buildValidatedEmbeddingService(
                    model = model,
                    provider = endpoint.provider,
                    pricingModel = endpoint.pricingModel,
                )
                else -> null
            }
            // The branch above built the capability that kind names, so the cast only checks what the types say.
            return built?.let { kind.capability.cast(it) }
        }
    }
}

/**
 * OpenAI: chat and embedding models.
 *
 * ```kotlin
 * val platform = OpenAiPlatform()
 * val embedding = platform.model(ProviderCredential(OpenAiModels.PROVIDER, key), EmbeddingService.KIND, OpenAiModels.TEXT_EMBEDDING_3_SMALL)
 * ```
 */
@ApiStatus.Experimental
class OpenAiPlatform @JvmOverloads constructor(
    timeouts: OpenAiClientTimeouts = OpenAiClientTimeouts.DEFAULT,
) : OpenAiCompatiblePlatform(
    provider = OpenAiCompatibleModelFactory.OPEN_AI.provider,
    baseUrl = OpenAiCompatibleModelFactory.OPEN_AI.baseUrl,
    kinds = CHAT_AND_EMBEDDING,
    timeouts = timeouts,
) {
    override fun withTimeouts(timeouts: OpenAiClientTimeouts): OpenAiPlatform = OpenAiPlatform(timeouts)
}

/**
 * DeepSeek: chat models only. DeepSeek's API has no embeddings endpoint, so a DeepSeek key asked
 * for an embedding model gets none from this platform.
 *
 * ```kotlin
 * val platform = DeepSeekPlatform()
 * val chat = platform.model(ProviderCredential(DeepSeekModels.PROVIDER, key), LlmService.KIND, DeepSeekModels.DEEPSEEK_V4_FLASH)
 * ```
 */
@ApiStatus.Experimental
class DeepSeekPlatform @JvmOverloads constructor(
    timeouts: OpenAiClientTimeouts = OpenAiClientTimeouts.DEFAULT,
) : OpenAiCompatiblePlatform(
    provider = OpenAiCompatibleModelFactory.DEEP_SEEK.provider,
    baseUrl = OpenAiCompatibleModelFactory.DEEP_SEEK.baseUrl,
    kinds = CHAT_ONLY,
    timeouts = timeouts,
) {
    override fun withTimeouts(timeouts: OpenAiClientTimeouts): DeepSeekPlatform = DeepSeekPlatform(timeouts)
}

/**
 * Mistral AI: chat and embedding models, both over Mistral's OpenAI-compatible endpoint.
 *
 * ```kotlin
 * val platform = MistralAiPlatform()
 * val embedding = platform.model(ProviderCredential(MistralAiModels.PROVIDER, key), EmbeddingService.KIND, "mistral-embed")
 * ```
 */
@ApiStatus.Experimental
class MistralAiPlatform @JvmOverloads constructor(
    timeouts: OpenAiClientTimeouts = OpenAiClientTimeouts.DEFAULT,
) : OpenAiCompatiblePlatform(
    provider = OpenAiCompatibleModelFactory.MISTRAL.provider,
    baseUrl = OpenAiCompatibleModelFactory.MISTRAL.baseUrl,
    kinds = CHAT_AND_EMBEDDING,
    timeouts = timeouts,
) {
    override fun withTimeouts(timeouts: OpenAiClientTimeouts): MistralAiPlatform = MistralAiPlatform(timeouts)
}
