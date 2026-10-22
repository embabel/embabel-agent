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
package com.embabel.common.ai.model

import org.jetbrains.annotations.ApiStatus

/**
 * A provider platform: the models that one user-supplied key can be used for.
 *
 * One provider key often works for several kinds of model. An OpenAI key works for chat models and
 * for embedding models, for example. A platform is registered once and builds a model of every
 * kind in [kinds], so the code that decides where the key is sent is written once.
 *
 * The platform uses this when a role resolves to [RoleResolution.Credential]. It asks each
 * registered platform whose [kinds] contain the kind it needs, in bean order, and uses the first
 * model that is not null.
 *
 * For chat, use this only when a [CredentialEndpointResolver] cannot do the job. A chat model is a
 * [com.embabel.agent.spi.LlmService], which is in the `com.embabel.agent.spi` package, and
 * application code is asked not to depend on that package. Most chat providers use the OpenAI or
 * Anthropic protocol. For those, register a [CredentialEndpointResolver] that returns the endpoint;
 * it needs no SPI type.
 *
 * `embabel-agent-starter-byok` ships one platform per wire protocol, covering every provider BYOK
 * supports, so per-user keys work with no application code at all; see
 * `com.embabel.agent.config.models.byok.CredentialEndpointConfig`. Each shipped bean is skipped
 * when the application registers a bean with the same name. A bean registered that way replaces
 * every kind the shipped platform builds, and also replaces the code that builds clients for the
 * endpoints [CredentialEndpointResolver]s return for that protocol.
 *
 * Kotlin:
 *
 * ```kotlin
 * @Bean
 * fun gatewayPlatform(): ModelPlatform =
 *     ModelPlatform.builder()
 *         // Build only for this provider; return null so other platforms handle the rest.
 *         .builds(EmbeddingService.KIND) { credential, model ->
 *             if (credential.provider == "gateway") GatewayEmbeddingService(credential.apiKey, model) else null
 *         }
 *         // A chat model is an LlmService, from com.embabel.agent.spi.
 *         .builds(LlmService.KIND) { credential, model ->
 *             if (credential.provider == "gateway") GatewayLlmService(credential.apiKey, model) else null
 *         }
 *         .build()
 * ```
 *
 * Java:
 *
 * ```java
 * @Bean
 * ModelPlatform gatewayPlatform() {
 *     return ModelPlatform.builder()
 *             .builds(EmbeddingService.KIND, (credential, model) ->
 *                     credential.getProvider().equals("gateway") ? new GatewayEmbeddingService(credential.getApiKey(), model) : null)
 *             .build();
 * }
 * ```
 *
 * Without a platform that builds the right kind for the provider, a role resolving to
 * [RoleResolution.Credential] fails with [NoSuitableModelException] and a log line naming the
 * provider nothing handled.
 *
 * Return null for a provider this platform does not handle, rather than building something: the
 * platform tries each model platform in turn, and one that answers for everything would hand back
 * a client pointed at the wrong endpoint for someone else's key.
 *
 * The platform caches what this returns, per (provider, key, model), so an implementation should
 * build rather than maintain a cache of its own.
 *
 * Implementations must be thread-safe: one instance serves every call, on any thread.
 */
@ApiStatus.Experimental
interface ModelPlatform {

    /**
     * The kinds this platform can build. This is the most a key can reach; a given key may reach
     * fewer, and then [model] returns null for the others.
     *
     * The platform asks only for kinds in this set. A kind equals only itself, so use each kind's
     * own constant, such as `LlmService.KIND` or [EmbeddingService.KIND].
     *
     * This set lists what this release of the integration can build today. A release that adds a
     * kind is additive. When a provider stops offering a capability, a later release removes that
     * kind from this set. Callers' code still compiles, and a role that needed the kind now fails
     * at selection with a clear message instead of a provider error. Until that release, the
     * provider's refusal is what the caller sees.
     *
     * Keep this a set of values. Do not express the kinds as interfaces the platform class
     * implements: removing an interface would break callers' code, and removing a value does not.
     */
    val kinds: Set<ModelKind<*, *>>

    /**
     * A model of [kind] named [model], built with [credential].
     *
     * @param credential the user's provider and key
     * @param kind the kind of model to build; always one of [kinds]
     * @param model the model name, for example "gpt-4.1-mini"
     * @return the model, or null if this platform does not serve the credential's provider or
     * cannot build that kind
     */
    fun <C : ModelCapability> model(credential: ProviderCredential, kind: ModelKind<C, *>, model: String): C?

    /**
     * Builds a [ModelPlatform] from one function per kind.
     *
     * Each call to [builds] returns a new builder, so a builder can be shared and extended
     * without changing it.
     */
    class Builder internal constructor(
        private val builders: List<KindBuilder<*>>,
    ) {

        /**
         * A builder that also builds models of [kind] with [build].
         *
         * @param kind the kind of model; may be given once per builder
         * @param build the credential and model name to a model, or null for a provider it does
         * not handle
         * @return a new builder
         */
        fun <C : ModelCapability> builds(
            kind: ModelKind<C, *>,
            build: (credential: ProviderCredential, model: String) -> C?,
        ): Builder {
            // Two functions for one kind would leave which one runs to list order, so a second one throws.
            require(builders.none { it.kind == kind }) { "$kind is already built by this platform" }
            return Builder(builders + KindBuilder(kind, build))
        }

        /** The platform, building each kind given to [builds]. */
        fun build(): ModelPlatform = FunctionModelPlatform(builders)
    }

    companion object {

        /**
         * An empty builder. Add each kind with [Builder.builds].
         *
         * ```kotlin
         * ModelPlatform.builder()
         *     .builds(LlmService.KIND) { credential, model -> chatModelFor(credential, model) }
         *     .builds(EmbeddingService.KIND) { credential, model -> embeddingModelFor(credential, model) }
         *     .build()
         * ```
         */
        @JvmStatic
        fun builder(): Builder = Builder(emptyList())
    }
}

/** One kind and the function that builds models of it. */
internal class KindBuilder<C : ModelCapability>(
    val kind: ModelKind<C, *>,
    private val buildModel: (credential: ProviderCredential, model: String) -> C?,
) {
    fun build(credential: ProviderCredential, model: String): C? = buildModel(credential, model)
}

private class FunctionModelPlatform(
    private val builders: List<KindBuilder<*>>,
) : ModelPlatform {

    override val kinds: Set<ModelKind<*, *>> = builders.map { it.kind }.toSet()

    override fun <C : ModelCapability> model(credential: ProviderCredential, kind: ModelKind<C, *>, model: String): C? =
        // At most one builder holds this kind, because Builder.builds rejects a second one.
        builders.firstOrNull { it.kind == kind }
            ?.build(credential, model)
            // The builder for this kind instance was given a function returning C, so the cast
            // only checks what the types already say.
            ?.let { kind.capability.cast(it) }

    override fun toString(): String = "ModelPlatform(${builders.joinToString { it.kind.name }})"
}

/**
 * A model of [kind] from the first of these platforms that lists [kind] and builds one for
 * [credential], or null if none does.
 */
internal fun <C : ModelCapability> List<ModelPlatform>.firstModel(
    credential: ProviderCredential,
    kind: ModelKind<C, *>,
    model: String,
): C? =
    // Ask only platforms that list this kind; the others were not written for it.
    filter { kind in it.kinds }
        .firstNotNullOfOrNull { it.model(credential, kind, model) }
