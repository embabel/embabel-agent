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
 * What a kind does when nothing satisfies a request - fall back to a placeholder, or fail - is a
 * policy, and belongs to the selection that uses a family, not to the family. See
 * [ChatModelSelection] and [EmbeddingModelSelection].
 *
 * @param kind the kind of model this family selects
 * @param registered the models registered at startup
 * @param roles what configuration says this kind's roles mean
 * @param resolvers application resolvers in order, then the platform's configurable one
 * @param modelPlatforms platforms that build a model of this kind from a user's key
 * @param catalogs what local runners are serving
 * @param credentialCacheSize how many models built from user keys to keep
 * @param rolesProperty the role properties a credential's model is looked up in, for messages, such as
 * `embabel.models.roles or embabel.models.llms`
 * @param isPlaceholder whether a registered model stands in for one that is awaiting a key
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
     * Logs as [ConfigurableModelProvider], as both selections do, so every line selection writes
     * stays under the one logger an operator already filters or captures.
     */
    private val logger = loggerFor<ConfigurableModelProvider>()

    /**
     * Models built from user keys, which are per-user and so cannot be Spring beans.
     * Bounded, and least-recently-used entries are dropped: an unbounded map here would retain a
     * model for every key the deployment has ever seen.
     */
    private val credentialModels: MutableMap<CredentialModelKey, C> =
        object : LinkedHashMap<CredentialModelKey, C>(16, 0.75f, true) {
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
     * The by-NAME counterpart of what [com.embabel.common.ai.model.local.LocalModelRoleResolver]
     * does for a role, and the reason a listing can name a model that arrived after startup
     * without lying: everything the platform lists is something a caller can then ask for.
     * Catalogs are tried in bean order, and the first that is serving the name answers - two
     * runners serving the same model name serve the same model, so there is nothing to choose
     * between them.
     */
    fun locallyServed(name: String): C? = catalogs.firstNotNullOfOrNull { it.named(kind, name) }

    /** Names of this kind a local runner is serving, for the listings. */
    fun locallyServedNames(): List<String> = catalogs.flatMap { it.servedNames(kind) }.distinct()

    /**
     * Every name a caller could ask for: registered at startup, or being served by a local runner
     * since. The same set listings report, so being told what is available and then asking for one
     * of them cannot disagree.
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
     * Structural rather than by name: `com.embabel.agent.spi` owns the markers, and this class
     * must not depend on the BYOK module that implements them.
     */
    fun placeholder(): C? = registered.firstOrNull(isPlaceholder)

    /**
     * What [resolution] materializes into, or null when it names a model this deployment cannot
     * serve.
     *
     * A [RoleResolution.Bound] matches registered models by name alone, placeholder included. A
     * deployment may point a role AT the placeholder deliberately - "this role has no model yet" -
     * and screening placeholders out here would refuse the one configuration that means to name
     * one. A role naming a model nothing registered still resolves to nothing, because the name
     * simply does not match.
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
     * Build - or reuse - a model for the one [role] names under the user's own provider.
     *
     * The lookup ends at the flat map. [ConfigurableRoleResolver.resolve] does not read the flat map
     * under a user key, because there it would return a model that the DEPLOYMENT's key pays for.
     * Here only the model NAME is read from it, and the model is built on the user's own key, so
     * the user pays for the model their role names. Reachable from the flat map only through an application resolver
     * returning [RoleResolution.Credential], since the configured one gets this far only when the
     * nested map already answered.
     */
    private fun fromCredential(role: String, credential: ProviderCredential): Resolved<C, B>? {
        val binding = roles.configuredFor(role, credential.provider)
        val model = binding?.modelName
        if (model == null) {
            logger.warn(
                "{} role '{}' has no model configured for provider '{}' under {}",
                kind.name, role, credential.provider, rolesProperty,
            )
            return null
        }
        val built = credentialModel(credential, model)
        if (built == null) {
            logger.warn(
                """Nothing built a {} model for provider '{}', needed for role '{}'. Register a CredentialEndpointResolver or a ModelPlatform that builds the {} kind for it, and check that the module that calls that provider's API is on the classpath""",
                kind.name, credential.provider, role, kind.name,
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
     * Concretely. Two requests for the same user arrive together, both resolving "cheapest" under
     * the same key, and neither finds a cached entry. With computeIfAbsent, the first holds the
     * map's lock across a network round trip, and the second blocks on it - as does every unrelated
     * lookup for every other user, because it is one map. Reading first, both build, both put, and
     * the second put wins. The key and model are identical, so the two are interchangeable; the
     * loser is dropped and the cost is one wasted call.
     *
     * Shared by the ways a credential reaches a model - a role that named it, and a caller that
     * named it - so the cache is one cache and the locking argument is made once.
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
     * Identifies the key by SHA-256 rather than holding it, so the plaintext is not duplicated
     * into a map that lives as long as the platform does. The cached model was built from the key
     * and still holds it, so this narrows the exposure rather than removing it - but it removes
     * the copy that exists purely for lookup, and keeps keys out of any heap dump taken of the
     * cache itself.
     *
     * A digest cannot collide in practice, and two users would have to share a provider AND a
     * model AND a SHA-256 collision to reach one another's model.
     */
    private data class CredentialModelKey(
        val provider: String,
        val apiKeyDigest: String,
        val model: String,
    ) {

        companion object {

            fun of(credential: ProviderCredential, model: String) = CredentialModelKey(
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
