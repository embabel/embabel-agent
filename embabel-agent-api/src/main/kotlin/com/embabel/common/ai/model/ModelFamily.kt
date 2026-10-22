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

import com.embabel.common.ai.model.local.LocalModelCatalog
import com.embabel.common.util.loggerFor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/**
 * Everything one [ModelKind]'s selection does the same way as every other kind's: ask the role
 * resolvers, build a model from a user's key and cache it, ask local runners, find the registered
 * placeholder, and list what can be asked for by name.
 *
 * What happens when nothing satisfies a request, returning the placeholder or throwing, differs by
 * kind. The selection that uses a family decides it, not the family. See [ChatModelSelection] and
 * [EmbeddingModelSelection].
 *
 * @param kind the kind of model this family selects
 * @param registered the models registered at startup
 * @param roles what configuration says this kind's roles mean
 * @param resolvers application resolvers in order, then the platform's configurable one
 * @param modelPlatforms platforms that build models from a user's key; only those listing [kind]
 *   are asked
 * @param catalogs what local runners are serving
 * @param credentialCacheSize how many models built from user keys to keep
 * @param rolesProperty the role properties a credential's model is looked up in, for messages, such as
 * `embabel.models.roles or embabel.models.llms`
 * @param isPlaceholder whether a registered model is the placeholder, which is registered while the
 * deployment has no key for any other model of this kind
 */
internal class ModelFamily<C, B : RoleBinding>(
    val kind: ModelKind<C, B>,
    val registered: List<C>,
    val roles: ConfiguredRoles<B>,
    val resolvers: List<RoleResolver<C, B>>,
    private val modelPlatforms: List<ModelPlatform>,
    private val catalogs: List<LocalModelCatalog>,
    credentialCacheSize: Int,
    private val rolesProperty: String,
    private val isPlaceholder: (C) -> Boolean,
) where C : ModelCapability, C : ModelMetadata {

    /**
     * Logs under the [ConfigurableModelProvider] logger, as both selections do, so an operator who
     * filters or captures that logger sees every model selection message.
     */
    private val logger = loggerFor<ConfigurableModelProvider>()

    /**
     * Models built from user keys. They belong to one user each, so they cannot be Spring beans.
     * The map holds at most `credentialCacheSize` models and drops the least recently used one when
     * it is full. Without a limit it would keep a model for every key the deployment has ever seen.
     */
    private val credentialModels: MutableMap<CredentialModelKey, C> =
        // 16 and 0.75 are LinkedHashMap's default capacity and load factor. `true` orders entries by
        // last access instead of insertion, so the eldest entry is the least recently used one.
        object : LinkedHashMap<CredentialModelKey, C>(16, 0.75f, true) {
            // Drop the least recently used entry once the map holds more than the configured size.
            override fun removeEldestEntry(eldest: Map.Entry<CredentialModelKey, C>) =
                size > credentialCacheSize
        }.let { Collections.synchronizedMap(it) }

    /** The first resolver's answer for [role], or null when none answers. */
    fun resolve(role: String, context: ModelSelectionContext): RoleResolution<C, B>? =
        resolvers.firstNotNullOfOrNull { it.resolve(role, context) }

    /** The model registered under [name], placeholder included. */
    fun registeredNamed(name: String): C? = registered.firstOrNull { it.name == name }

    /**
     * A model of this kind a local runner is serving under this exact name, or null.
     *
     * [com.embabel.common.ai.model.local.LocalModelRoleResolver] does this for a role; this does it
     * for a name. Because of it, a model that a listing reports after startup can also be asked for
     * by name. Catalogs are tried in bean order and the first that serves the name answers. Two
     * runners that serve the same model name serve the same model, so the order does not matter.
     */
    fun locallyServed(name: String): C? = catalogs.firstNotNullOfOrNull { it.named(kind, name) }

    /** Names of this kind a local runner is serving, for the listings. */
    fun locallyServedNames(): List<String> = catalogs.flatMap { it.servedNames(kind) }.distinct()

    /**
     * Every name a caller can ask for: the names registered at startup and the names a local runner
     * is serving now. The listings report the same set, so every name a listing reports can be asked
     * for.
     */
    fun listableNames(): List<String> = (registered.map { it.name } + locallyServedNames()).distinct()

    /** Names a local runner is serving that nothing registered at startup, by catalog provider. */
    fun unregisteredLocalNames(): List<LocalName> =
        catalogs.flatMap { catalog ->
            catalog.servedNames(kind)
                .filter { name -> registered.none { it.name == name } }
                .map { LocalName(it, catalog.provider) }
        }

    /** A model a local runner is serving that nothing registered at startup. */
    data class LocalName(val name: String, val provider: String)

    /**
     * The registered placeholder, if this deployment carries one.
     *
     * Found with [isPlaceholder] rather than by name. The markers are
     * [com.embabel.agent.spi.PlaceholderLlmService] for chat and [EmbeddingService.awaitingProviderKey]
     * for embedding, and this class must not depend on the BYOK module that implements them.
     */
    fun placeholder(): C? = registered.firstOrNull(isPlaceholder)

    /**
     * What [resolution] materializes into, or null when it names a model this deployment cannot
     * serve.
     *
     * A [RoleResolution.Bound] matches registered models by name alone, placeholder included. A
     * deployment may point a role at the placeholder on purpose, to say "this role has no model
     * yet". If placeholders were skipped here, that configuration would resolve to nothing. A role
     * naming a model that nothing registered still resolves to nothing, because no name matches.
     */
    fun materialize(role: String, resolution: RoleResolution<C, B>?): Resolved<C, B>? =
        when (resolution) {
            is RoleResolution.Service -> Resolved(resolution.service, resolution.binding)
            is RoleResolution.Bound ->
                resolution.binding.modelName?.let(::registeredNamed)?.let { Resolved(it, resolution.binding) }
            is RoleResolution.Credential -> fromCredential(role, resolution.credential)
            null -> null
        }

    /**
     * Builds, or reuses from the cache, the model that [role] names for the provider of the user's
     * key.
     *
     * The model name is looked up in the nested role map for the user's provider, then in the flat
     * role map. [ConfigurableRoleResolver.resolve] does not read the flat map when a user key is
     * active, because there it would serve a model built on the deployment's key, and the
     * deployment would pay. Here only the model name is read from the flat map, and the model is
     * built on the user's key, so the user pays.
     *
     * The flat map is only reached here when an application resolver returns
     * [RoleResolution.Credential]. [ConfigurableRoleResolver] returns one only when the nested map
     * already has an entry for the user's provider.
     */
    private fun fromCredential(role: String, credential: ProviderCredential): Resolved<C, B>? {
        val binding = roles.configuredFor(role, credential.provider)
        val model = binding?.modelName
        // The binding found for this role and provider names no model, so there is nothing to build.
        if (model == null) {
            logger.warn(
                "{} role '{}' has no model configured for provider '{}' under {}",
                kind.name, role, credential.provider, rolesProperty,
            )
            return null
        }
        val built = credentialModel(credential, model)
        // No model platform could build the model for this provider.
        if (built == null) {
            logger.warn(
                """
                No model platform built a {} model for provider '{}', which role '{}' needs.
                Register a CredentialEndpointResolver or a ModelPlatform that builds {} models for provider '{}',
                and check that the module that calls that provider's API is on the classpath
                """.trimIndent(),
                kind.name, credential.provider, role, kind.name, credential.provider,
            )
            return null
        }
        return Resolved(built, binding)
    }

    /**
     * The cached model for this (provider, key, model), or one freshly built and cached.
     *
     * Read then put rather than computeIfAbsent: building a model can validate the key or observe
     * an embedding width over the network, and computeIfAbsent would hold the map's lock for the
     * duration.
     *
     * For example, two requests for the same user arrive together, both resolving "cheapest" under
     * the same key, and neither finds a cached entry. With computeIfAbsent, the first holds the
     * map's lock across a network round trip, and the second waits for it. Every lookup for every
     * other user waits too, because all users share this map. Reading first, both build, both put,
     * and the second put wins. Both models were built from the same key and model name, so either can be
     * used; the one put first is dropped and the cost is one wasted call.
     *
     * Used both when a role resolves to a credential and when a caller names a model to build on
     * the deployment's credential, so both use the same cache.
     */
    fun credentialModel(credential: ProviderCredential, model: String): C? {
        val key = CredentialModelKey.of(credential, model)
        return credentialModels[key]
            ?: modelPlatforms.firstModel(credential, kind, model)
                ?.also { credentialModels[key] = it }
    }

    /**
     * A role, materialized: the model to call, and the binding configured alongside it.
     *
     * @param binding null when a resolver built the model itself and had nothing to add
     */
    data class Resolved<C, B : RoleBinding>(val model: C, val binding: B?)

    /**
     * Cache key for a model built from a user's key.
     *
     * Holds the SHA-256 digest of the key instead of the key, so the map, which lives as long as
     * the platform, does not hold another plaintext copy. The cached model was built from the key
     * and still holds it, so the key stays in memory. The digest removes only the extra copy a
     * plaintext map key would add, and keeps the key out of the map's keys in a heap dump.
     *
     * For one user to get another user's model, both would need the same provider, the same model
     * name, and keys with the same SHA-256 digest. SHA-256 digests do not collide in practice.
     */
    private data class CredentialModelKey(
        val provider: String,
        val apiKeyDigest: String,
        val model: String,
    ) {

        companion object {

            fun of(credential: ProviderCredential, model: String) = CredentialModelKey(
                // Lowercased so "OpenAI" and "openai" share one cache entry.
                provider = credential.provider.lowercase(),
                apiKeyDigest = digest(credential.apiKey),
                model = model,
            )

            private fun digest(apiKey: String): String =
                MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
        }
    }
}
