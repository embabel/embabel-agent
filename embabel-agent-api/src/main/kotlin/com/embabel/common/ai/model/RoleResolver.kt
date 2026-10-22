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
 * What a role resolved to, for one [ModelKind].
 *
 * Three cases, in increasing order of how much the resolver does itself. Most applications need
 * only [Credential]: say whose key is active and let the platform do the rest.
 *
 * @param C the capability of the kind the role was resolved for
 * @param B what a role of that kind binds to
 */
@ApiStatus.Experimental
sealed interface RoleResolution<out C : ModelCapability, out B : RoleBinding> {

    /**
     * A configured binding: a model name, and for chat the hyperparameters that travel with the
     * role. Values the caller set explicitly still win.
     *
     * The binding is wrapped so that the platform can tell this case apart from the other two in a
     * `when`, and so that a field can be added to this case later without changing every
     * resolver's signature.
     */
    data class Bound<out B : RoleBinding>(
        val binding: B,
    ) : RoleResolution<Nothing, B>

    /**
     * A provider key. The platform looks the role up for that provider and builds the model through
     * a [CredentialModelFactory] of the same kind, caching it.
     */
    data class Credential(
        val credential: ProviderCredential,
    ) : RoleResolution<Nothing, Nothing>

    /**
     * An already-built model, for callers that construct their own.
     *
     * [binding] travels with it and is merged the way [Bound] is, so a resolver that built its own
     * model does not have to give up the tuning configured beside the model name. Null for a
     * resolver handing back a model it configured itself.
     */
    data class Service<out C : ModelCapability, out B : RoleBinding> @JvmOverloads constructor(
        val service: C,
        val binding: B? = null,
    ) : RoleResolution<C, B>
}

/**
 * Decides what a role means for a given call, for one [ModelKind].
 *
 * Register as many as you like. The platform asks only the resolvers of the kind being selected,
 * so a role name shared between kinds - "cheapest" for chat and for embedding - cannot resolve to a
 * model of the wrong kind. Within a kind it takes the first non-null answer, so a resolver can
 * handle the roles it cares about and delegate the rest by returning null.
 *
 * Ordering, in full, because it decides who wins:
 *
 * - **Application resolvers always precede the platform's own.** [ConfigurableRoleResolver] is not
 *   in the bean stream at all - it is appended after the ordered beans - so configuration is
 *   always the last word, whatever an application registers.
 * - **Between application resolvers**, Spring's [org.springframework.core.Ordered] applies:
 *   lowest value first, via `@Order` or by implementing `Ordered`. A resolver that does neither is
 *   `LOWEST_PRECEDENCE` and sorts after any that do.
 * - **Ties** fall back to bean registration order, which is not something to depend on. If two of
 *   your resolvers can answer the same role, order them explicitly.
 *
 * Implementations must be thread-safe: one instance serves every call, on any thread.
 *
 * ```kotlin
 * @Bean
 * fun byokRoleResolver(userKeyStore: UserKeyStore): RoleResolver<LlmService<*>, LlmOptions> =
 *     RoleResolver.of(LlmService.KIND) { _, context ->
 *         // Use the user's own key when they have one; null lets configuration decide.
 *         context.userId
 *             ?.let { userKeyStore.activeKeyFor(it) }
 *             ?.let { RoleResolution.Credential(ProviderCredential(it.provider, it.apiKey)) }
 *     }
 * ```
 *
 * @param C the capability of the kind this resolver answers for
 * @param B what a role of that kind binds to
 */
@ApiStatus.Experimental
interface RoleResolver<C : ModelCapability, B : RoleBinding> {

    /** The kind of model this resolver answers for. */
    val kind: ModelKind<C, B>

    /**
     * @param role the role requested, for example "cheapest"
     * @param context who the call is for and which provider key is active
     * @return how to satisfy the role, or null to let the next resolver decide
     */
    fun resolve(role: String, context: ModelSelectionContext): RoleResolution<C, B>?

    companion object {

        /**
         * A resolver of [kind] that answers with [resolve].
         *
         * @param kind the kind of model the resolver answers for
         * @param resolve the role and context to an answer, or null to let the next resolver decide
         * @return the resolver
         */
        @JvmStatic
        fun <C : ModelCapability, B : RoleBinding> of(
            kind: ModelKind<C, B>,
            resolve: (role: String, context: ModelSelectionContext) -> RoleResolution<C, B>?,
        ): RoleResolver<C, B> = FunctionRoleResolver(kind, resolve)
    }
}

private class FunctionRoleResolver<C : ModelCapability, B : RoleBinding>(
    override val kind: ModelKind<C, B>,
    private val answer: (role: String, context: ModelSelectionContext) -> RoleResolution<C, B>?,
) : RoleResolver<C, B> {
    override fun resolve(role: String, context: ModelSelectionContext): RoleResolution<C, B>? = answer(role, context)
    override fun toString(): String = "RoleResolver(${kind.name})"
}

/**
 * Builds a model of one [ModelKind] from a user-supplied key, for a wire protocol the framework has
 * no client for.
 *
 * For chat, use this only when a [CredentialEndpointResolver] cannot do the job. A chat factory
 * returns [com.embabel.agent.spi.LlmService], which is in the `com.embabel.agent.spi` package, and
 * application code is asked not to depend on that package. Most chat providers use the OpenAI or
 * Anthropic protocol. For those, register a [CredentialEndpointResolver] that returns the endpoint;
 * it needs no SPI type.
 *
 * `embabel-agent-starter-byok` ships one implementation per wire protocol, covering every provider
 * BYOK supports, so per-user keys work with no application code at all; see
 * `com.embabel.agent.config.models.byok.CredentialEndpointConfig`. Each shipped bean is skipped
 * when the application registers a bean with the same name. A bean registered that way also
 * replaces the code that builds clients for the endpoints [CredentialEndpointResolver]s return for
 * that protocol.
 *
 * ```kotlin
 * @Bean
 * fun gatewayEmbeddingFactory(): CredentialModelFactory<EmbeddingService> =
 *     CredentialModelFactory.of(EmbeddingService.KIND) { credential, model ->
 *         // Build only for this provider; return null so other factories handle the rest.
 *         if (credential.provider == "gateway") GatewayEmbeddingService(credential.apiKey, model) else null
 *     }
 * ```
 *
 * Without a factory of the right kind that handles the provider, a role resolving to
 * [RoleResolution.Credential] fails with [NoSuitableModelException] and a log line naming the
 * provider nothing handled.
 *
 * Return null for a provider this factory does not handle, rather than building something: the
 * platform tries each factory of the kind in turn, and a factory that answers for everything would
 * hand back a client pointed at the wrong endpoint for someone else's key.
 *
 * The platform caches what this returns, per (provider, key, model), so an implementation should
 * build rather than maintain a cache of its own.
 *
 * @param C the capability of the kind this factory builds
 */
@ApiStatus.Experimental
interface CredentialModelFactory<C : ModelCapability> {

    /** The kind of model this factory builds. */
    val kind: ModelKind<C, *>

    /**
     * @return a model for [model] authenticated with [credential], or null if this factory does
     * not handle that provider
     */
    fun create(credential: ProviderCredential, model: String): C?

    companion object {

        /**
         * A factory of [kind] that builds with [create].
         *
         * @param kind the kind of model the factory builds
         * @param create the credential and model name to a model, or null for a provider it does
         * not handle
         * @return the factory
         */
        @JvmStatic
        fun <C : ModelCapability> of(
            kind: ModelKind<C, *>,
            create: (credential: ProviderCredential, model: String) -> C?,
        ): CredentialModelFactory<C> = FunctionCredentialModelFactory(kind, create)
    }
}

private class FunctionCredentialModelFactory<C : ModelCapability>(
    override val kind: ModelKind<C, *>,
    private val build: (credential: ProviderCredential, model: String) -> C?,
) : CredentialModelFactory<C> {
    override fun create(credential: ProviderCredential, model: String): C? = build(credential, model)
    override fun toString(): String = "CredentialModelFactory(${kind.name})"
}

/** The resolvers of [kind], in order. */
internal fun <C : ModelCapability, B : RoleBinding> List<RoleResolver<*, *>>.ofKind(
    kind: ModelKind<C, B>,
): List<RoleResolver<C, B>> =
    // Kinds are matched by identity. A resolver that holds this exact kind instance was declared
    // RoleResolver<C, B>, so the cast is safe.
    @Suppress("UNCHECKED_CAST")
    (filter { it.kind === kind } as List<RoleResolver<C, B>>)

/** The credential factories of [kind], in order. */
@JvmName("credentialModelFactoriesOfKind")
internal fun <C : ModelCapability> List<CredentialModelFactory<*>>.ofKind(
    kind: ModelKind<C, *>,
): List<CredentialModelFactory<C>> =
    // A factory that holds this exact kind instance was declared CredentialModelFactory<C>, so the
    // cast is safe.
    @Suppress("UNCHECKED_CAST")
    (filter { it.kind === kind } as List<CredentialModelFactory<C>>)
