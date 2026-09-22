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

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.PlaceholderLlmService
import com.embabel.common.util.indent
import com.embabel.common.util.loggerFor
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import com.embabel.common.ai.model.local.LateArrivingModels
import com.embabel.common.ai.model.local.LocalModelCatalog
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.ai.model.local.LocalModelKind
import com.embabel.common.ai.model.local.LocalModelRoleResolver

/**
 * Configuration properties for the model provider
 */
@Validated
@ConfigurationProperties("embabel.models")
data class ConfigurableModelProviderProperties(
    /**
     *  Map of role to LLM name. Each entry will require an LLM to be registered with the same name. May not include the default LLM.
     */
    var llms: Map<String, String> = emptyMap(),
    /**
     * Map of role to embedding service name. May not include the default embedding service.
     */
    var embeddingServices: Map<String, String> = emptyMap(),
    /**
     * The deployment default: either an LLM name, or a role from [llms] or [roles]. It's good
     * practice to override this in configuration.
     *
     * A model name is resolved once, against the services registered at startup. A role is resolved
     * per call, through the same [RoleResolver] chain as any other role - which is what lets a
     * deployment whose key arrives at runtime have a working default without a restart. A model name
     * that nothing registers falls back to the role chain too, so `default-llm: gpt-5.6-luna` keeps
     * working whichever way it is meant.
     */
    var defaultLlm: String = "gpt-5.6-luna",
    /**
     *  Default embedding model name. Must be an embedding model name. Need not be set, in which case it defaults to null.
     */
    var defaultEmbeddingModel: String? = null,
    /**
     * Map of role to provider to options, for deployments whose provider is not fixed - a
     * bring-your-own-key application, or one configured for failover across providers.
     *
     * ```yaml
     * embabel:
     *   models:
     *     roles:
     *       cheapest:
     *         openai:    { model: gpt-4.1-nano }
     *         anthropic: { model: claude-haiku-4-5 }
     * ```
     *
     * Takes precedence over [llms] for the active provider.
     *
     * Whether an unregistered model here is an error depends on WHOSE provider it is under. Under
     * the deployment's own provider the rule is the same as [llms] - fatal at startup, because it
     * is a typo. Under any other provider it is not checked at all: that entry exists for a user
     * who brings a key for that provider, so its model is not expected to be registered here.
     *
     * A deployment awaiting a key is the exception to both, and warns rather than failing - nothing
     * is registered yet, so no name can resolve and none of it is a typo.
     *
     * Declared last, despite belonging with [llms], so that adding it does not renumber the
     * existing parameters for anyone constructing this positionally.
     */
    var roles: Map<String, Map<String, LlmOptions>> = emptyMap(),
    /**
     * Upper bound on LLM services built from user-supplied keys and held for reuse.
     *
     * A cache bound is an operational concern: it trades memory against how often a deployment
     * rebuilds a service for a key it has seen before, and the right number depends on how many
     * distinct keys are concurrently active — which only the deployment knows. The default suits
     * a deployment with tens to low hundreds of concurrent users; raise it if yours has more, and
     * expect roughly one thin chat-client wrapper per entry.
     *
     * Exceeding it is not an error. Least-recently-used entries are dropped and rebuilt on next
     * use, so the only cost of setting it too low is repeated construction.
     *
     * Must be at least 1, checked at startup. Zero or negative would evict every entry as soon as
     * it was inserted, so the cache would silently never hold anything and every call would rebuild
     * a service over the network - indistinguishable from a caching bug, and only visible as
     * latency. Rejected rather than tolerated for that reason.
     */
    var credentialServiceCacheSize: Int = 500,
    /**
     * Map of role to provider to embedding model name, for deployments whose provider is not fixed
     * - a bring-your-own-key application, or one configured for failover across providers.
     *
     * ```yaml
     * embabel:
     *   models:
     *     embedding-roles:
     *       documents:
     *         openai:  text-embedding-3-small
     *         mistral: mistral-embed
     * ```
     *
     * Takes precedence over [embeddingServices] for the active provider.
     *
     * Flatter than [roles], which maps to a whole [LlmOptions]. An embedding model takes text and
     * returns a vector: there is no temperature, no token budget, and the one parameter that does
     * vary - the width - is the model's to state and the index's to follow, not the caller's to
     * ask for. A name is the whole of what a deployment has to say here.
     *
     * Declared last, despite belonging with [embeddingServices], so that adding it does not
     * renumber the existing parameters for anyone constructing this positionally.
     */
    var embeddingRoles: Map<String, Map<String, String>> = emptyMap(),
) {

    /**
     * Every LLM name this configuration mentions, across all three places one can appear: the flat
     * `llms` role map, the nested `roles` map (one model per provider), and `default-llm`.
     *
     * "Well known" means named in configuration, NOT registered or reachable. A BYOK deployment
     * names models for providers it holds no key for, and that is the point of the nested shape -
     * so this set is a superset of what [listModelNames] reports, and asking for one of these can
     * still fail. Used to decide which model names a deployment is entitled to talk about, not
     * which it can serve.
     */
    fun allWellKnownLlmNames(): Set<String> {
        return llms.values.toSet() + roles.values.flatMap { it.values }.mapNotNull { it.modelName } +
            // A role names no model of its own; the models it can resolve to are already in here
            // via the two maps above, and adding the role would offer callers a model name that
            // does not exist.
            if (defaultLlmNamesRole()) emptySet() else setOf(defaultLlm)
    }

    /**
     * Whether [defaultLlm] names a role from [llms] or [roles] rather than a model.
     *
     * Configuration alone decides this: a name that appears as a role key is a role. It cannot
     * collide with a model name, because a role and the model it names are the two sides of one
     * entry and nothing would map a role to itself.
     */
    fun defaultLlmNamesRole(): Boolean = llms.containsKey(defaultLlm) || roles.containsKey(defaultLlm)

    /**
     * The embedding counterpart of [allWellKnownLlmNames], case for case: the flat map, the
     * per-provider map, and `default-embedding-model` unless it names a role. Shorter only because
     * an [embeddingRoles] entry is a model NAME where a [roles] entry is a whole [LlmOptions].
     */
    fun allWellKnownEmbeddingServiceNames(): Set<String> {
        return embeddingServices.values.toSet() + embeddingRoles.values.flatMap { it.values } +
            // A role names no model of its own; the models it can resolve to are already in here
            // via the two maps above, and adding the role would offer callers a model name that
            // does not exist.
            if (defaultEmbeddingModelNamesRole()) emptySet() else setOfNotNull(defaultEmbeddingModel)
    }

    /**
     * Whether [defaultEmbeddingModel] names a role from [embeddingServices] or [embeddingRoles]
     * rather than a model.
     *
     * The embedding counterpart of [defaultLlmNamesRole], and what lets the embedding default
     * resolve per call: a name that matches a registered service is resolved once at startup, and
     * a role goes through the resolver chain on every call, so a key arriving after boot satisfies
     * the default without a restart.
     *
     * Configuration alone decides this: a name that appears as a role key is a role. It cannot
     * collide with a model name, because a role and the model it names are the two sides of one
     * entry and nothing would map a role to itself.
     */
    fun defaultEmbeddingModelNamesRole(): Boolean =
        defaultEmbeddingModel?.let { namesEmbeddingRole(it) } == true

    /**
     * Whether CONFIGURATION declares [name] as an embedding role.
     *
     * Deliberately configuration only, and not "some resolver would answer for it". An application
     * resolver may answer any role it likes, including every one, so asking the chain would let a
     * catch-all resolver claim a name that was meant to be a model - and a caller naming a model
     * would silently get whatever that resolver felt a role of that name should be. Declared here
     * or it is not a role.
     */
    fun namesEmbeddingRole(name: String): Boolean =
        embeddingServices.containsKey(name) || embeddingRoles.containsKey(name)
}

/**
 * Take LLM definitions from configuration
 */
class ConfigurableModelProvider @JvmOverloads constructor(
    private val llms: List<LlmService<*>>,
    private val embeddingServices: List<EmbeddingService>,
    private val properties: ConfigurableModelProviderProperties,
    roleResolvers: List<RoleResolver> = emptyList(),
    private val credentialLlmServiceFactories: List<CredentialLlmServiceFactory> = emptyList(),
    embeddingRoleResolvers: List<EmbeddingRoleResolver> = emptyList(),
    private val credentialEmbeddingServiceFactories: List<CredentialEmbeddingServiceFactory> = emptyList(),
    private val localModelCatalogs: List<LocalModelCatalog> = emptyList(),
) : ModelProvider {

    private val logger = loggerFor<ConfigurableModelProvider>()

    private val configurableRoleResolver =
        ConfigurableRoleResolver(properties) { defaultLlm.provider }

    /**
     * Application resolvers first, the configuration-driven one last, so an application can
     * override any role and ignore the rest.
     */
    private val roleResolvers: List<RoleResolver> = roleResolvers + configurableRoleResolver

    /**
     * Services built from user keys, which are per-user and so cannot be Spring beans.
     * Bounded, and least-recently-used entries are dropped: an unbounded map here would retain a
     * service for every key the deployment has ever seen.
     */
    private val credentialLlmServices: MutableMap<CredentialModelKey, LlmService<*>> =
        object : LinkedHashMap<CredentialModelKey, LlmService<*>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<CredentialModelKey, LlmService<*>>) =
                size > properties.credentialServiceCacheSize
        }.let { Collections.synchronizedMap(it) }

    private val configurableEmbeddingRoleResolver =
        ConfigurableEmbeddingRoleResolver(properties) { defaultEmbeddingProviderName() }

    /**
     * Application resolvers first, the configuration-driven one last - the same ordering as
     * [roleResolvers], and a separate chain for the reason [EmbeddingRoleResolution] is a separate
     * type: a resolver that answers "cheapest" for chat must not be asked what it means for a
     * vector.
     */
    private val embeddingRoleResolvers: List<EmbeddingRoleResolver> =
        embeddingRoleResolvers + configurableEmbeddingRoleResolver

    /**
     * Providers whose models may appear after startup, declared by the resolvers that cover them.
     *
     * Read by [checkNestedRoles] and nothing else. It is not part of resolution: what a role means
     * is the chain's business, and this only decides whether an unregistered name is a typo worth
     * refusing to start over. See [LateArrivingModels].
     *
     * Catalogs are read as well as resolvers, because a shipped runner's catalog is what knows
     * whether discovery is on - resolvers for it exist either way, so asking them would excuse a
     * provider that has been configured never to be asked.
     */
    private val lateArrivingProviders: Set<String> =
        (this.roleResolvers + this.embeddingRoleResolvers + localModelCatalogs)
            .filterIsInstance<LateArrivingModels>()
            .mapNotNull { it.lateArrivingProvider?.lowercase() }
            .toSet()

    /**
     * The embedding counterpart of [credentialLlmServices], bounded the same way and for the same
     * reason: one entry per (provider, key, model) the deployment has served, and an unbounded map
     * would retain one for every key it has ever seen.
     */
    private val credentialEmbeddingServices: MutableMap<CredentialModelKey, EmbeddingService> =
        object : LinkedHashMap<CredentialModelKey, EmbeddingService>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<CredentialModelKey, EmbeddingService>) =
                size > properties.credentialServiceCacheSize
        }.let { Collections.synchronizedMap(it) }

    /**
     * The registered service `default-llm` names directly, or null when it names a role - or a
     * model nothing has registered.
     *
     * Null is what sends the default through the role chain on every call, so it is also the thing
     * that keeps a *registered* default off that path: a name that resolves here is resolved once,
     * at startup, exactly as before.
     */
    private val registeredDefaultLlm: LlmService<*>? = llms.firstOrNull { it.name == properties.defaultLlm }

    /**
     * What the default resolves to when nothing better is available - the deployment's provider for
     * role resolution, and the answer to [DefaultModelSelectionCriteria] when the role chain
     * declines.
     *
     * Resolved against whichever role map names a model this deployment has registered: the flat
     * `llms` map first, since that is the single-provider shape and says what the default means
     * without further qualification, then the nested `roles` map.
     */
    private val defaultLlm =
        if (llms.isNotEmpty())
            registeredDefaultLlm
                ?: flatRoleDefaultLlm()
                ?: nestedRoleDefaultLlm()
                ?: placeholderLlm()
                ?: throw IllegalArgumentException(unresolvableDefaultLlmMessage())
        else
            throw IllegalArgumentException("No models detected. Ensure that at least one Embabel Agent Starter (e.g. embabel-agent-starter-openai) is on the classpath and models are loaded into it.")

    /**
     * The registered service that `default-llm` names via the flat role map, if it names a role
     * that one of them satisfies.
     */
    private fun flatRoleDefaultLlm(): LlmService<*>? =
        properties.llms[properties.defaultLlm]?.let { model -> llms.firstOrNull { it.name == model } }

    /**
     * The registered service a nested `roles` entry for `default-llm` names, taking whichever
     * provider column this deployment can actually serve.
     *
     * The provider cannot be used to pick the column, because the provider is what this value is
     * being computed to supply. Taking whichever column a registered model answers for is the same
     * question from the other end: a deployment can only serve the providers it has models for, and
     * where it has several, any of them is a defensible startup default. Per-call resolution still
     * picks the right column for whichever key is active.
     *
     * The nested shape is not only for deployments awaiting a key. One that holds its own key may
     * use it to say what each role means per provider, against the day it serves users who bring
     * theirs - and such a deployment has no placeholder to fall back to. Without this it could not
     * name a role as its default at all: it failed to start, and was told the role it had just
     * configured was not a role.
     */
    private fun nestedRoleDefaultLlm(): LlmService<*>? =
        properties.roles[properties.defaultLlm]
            ?.values
            ?.mapNotNull { it.modelName }
            ?.firstNotNullOfOrNull { model -> llms.firstOrNull { it.name == model } }

    /**
     * Why `default-llm` resolved to nothing, said in the terms the reader has to act on.
     *
     * A configured role that nothing satisfies is a different problem from a name that is not a
     * role at all, and telling someone their role is not a role sends them to fix the one thing
     * that is already right.
     */
    private fun unresolvableDefaultLlmMessage(): String =
        if (properties.defaultLlmNamesRole())
            "Default LLM '${properties.defaultLlm}' is a configured role, but nothing this deployment has registered satisfies it. Point the role at one of the available models: ${llms.map { it.name }}, or set 'embabel.models.default-llm' to one of them directly."
        else
            "Default LLM '${properties.defaultLlm}' is neither a registered model nor a configured role. Set the 'embabel.models.default-llm' property to one of the available models: ${llms.map { it.name }}, or to one of the roles: ${(properties.llms.keys + properties.roles.keys).toList()}."

    /**
     * Whether this deployment is waiting for a key rather than misconfigured.
     *
     * True exactly when `default-llm` resolved to a [PlaceholderLlmService] - either because it
     * names one, or because the model it names is not registered and a placeholder stands in. That
     * is the deployment stating that keys arrive at runtime, and it is the only thing that makes an
     * unresolvable model name in configuration expected rather than a typo.
     *
     * A deployment that has a key resolves `default-llm` to a real model and so is never in this
     * mode, even with a placeholder registered alongside - which is what a BYOK starter next to a
     * provider starter looks like.
     */
    private val setupRequired: Boolean = defaultLlm is PlaceholderLlmService

    /**
     * The registered placeholder, if this deployment carries one.
     *
     * Deliberately structural rather than by name: `com.embabel.agent.spi` owns the marker, and
     * this class must not depend on the BYOK module that implements it.
     */
    private fun placeholderLlm(): LlmService<*>? =
        llms.firstOrNull { it is PlaceholderLlmService }
            ?.also {
                // Named, because degrading a real model to the placeholder would otherwise hide the
                // case where the key IS set and the model simply failed to register.
                //
                // A role is reported differently: it has not failed to resolve, it simply resolves
                // per call, and the placeholder is only what it falls back to on a call nothing can
                // satisfy. Warning about it at startup would fire on every boot of exactly the
                // deployment this is meant to serve.
                if (properties.defaultLlmNamesRole()) {
                    logger.info(
                        """
                        Default LLM '{}' is a role, and will be resolved per call.
                        Until a key is supplied it falls back to the '{}' placeholder
                        """.trimIndent(),
                        properties.defaultLlm, it.name,
                    )
                } else {
                    logger.warn(
                        """
                        Default LLM '{}' is not registered; falling back to the '{}' placeholder.
                        Calls will fail with an actionable 'no LLM configured' error until a key is supplied. Available: {}
                        """.trimIndent(),
                        properties.defaultLlm, it.name, llms.map { it.name },
                    )
                }
            }

    // Compute this lazily as embedding services may not be available
    private fun defaultEmbeddingService() =
        resolveDefaultEmbeddingService(warnOnFallback = true)
            ?: throw IllegalArgumentException("Default embedding service '${properties.defaultEmbeddingModel}' not found in available models: ${embeddingServices.map { it.name }}")

    /**
     * The registered service `default-embedding-model` names directly, or null when it names a
     * role - or a model nothing has registered.
     *
     * The counterpart of [registeredDefaultLlm], and it plays the same part: a name that resolves
     * here is resolved ONCE, at startup, so the ordinary keyed-at-boot deployment pays nothing for
     * per-call resolution and cannot have its default changed underneath it. Null is what sends
     * the default through the resolver chain on every call.
     */
    private val registeredDefaultEmbeddingService: EmbeddingService? =
        properties.defaultEmbeddingModel
            ?.takeIf { !properties.defaultEmbeddingModelNamesRole() }
            ?.let { name -> embeddingServices.firstOrNull { it.name == name } }

    /**
     * What `default-embedding-model` resolves to, or null if it resolves to nothing.
     *
     * Separate from [defaultEmbeddingService] because [embeddingSetupRequired] has to ask the
     * question during construction, where throwing would take down the deployments this whole
     * mechanism exists to let start - one with no embedding configuration at all resolves to
     * nothing here and must still boot, since nothing has asked for an embedding yet.
     *
     * A ROLE resolves per call, through the chain, exactly as `default-llm` does - which is the
     * whole point of letting the default name one. That path is taken only when configuration says
     * the name IS a role; anything else keeps the resolved-once behaviour it always had.
     */
    private fun resolveDefaultEmbeddingService(warnOnFallback: Boolean): EmbeddingService? {
        registeredDefaultEmbeddingService?.let { return it }
        if (properties.defaultEmbeddingModelNamesRole()) {
            properties.defaultEmbeddingModel?.let { role ->
                attemptEmbeddingRole(role, ModelSelectionContextHolder.get())?.let { return it }
            }
        } else {
            // A default naming a MODEL, resolved once at startup against registered services and
            // so null here when the model had not been pulled yet. Asking the runners is what lets
            // `default-embedding-model: <a local model>` start working on the pull rather than on
            // the next restart - the same per-call treatment a role already gets.
            properties.defaultEmbeddingModel?.let { model ->
                locallyServedEmbedding(model)?.let { return it }
            }
        }
        return placeholderEmbeddingService(warn = warnOnFallback)
    }

    /**
     * The provider behind the deployment's default embedding service, for
     * [ConfigurableEmbeddingRoleResolver] to pick a column with when no user key is active.
     *
     * Reads only what is REGISTERED, never the role chain: the chain is what calls this, and
     * asking it back would recurse. A deployment whose default is a role and whose key has not
     * arrived has no provider to offer here, which is correct - there is nothing to select within
     * until a key says otherwise.
     *
     * Where there is no registered default to read a provider off, this answers only when the
     * registered services agree on ONE provider. Two of them and it declines, rather than picking
     * the first: the order of [embeddingServices] is bean registration order, so a first-one-wins
     * tie-break would decide which column of `embedding-roles` a keyless call reads, and decide it
     * differently on a deployment that added a module. Declining sends the role to the flat
     * `embedding-services` map, which is the shape a deployment with no active key has an answer in.
     */
    private fun defaultEmbeddingProviderName(): String? =
        registeredDefaultEmbeddingService?.provider
            ?: embeddingServices.filterNot { it.awaitingProviderKey }
                .map { it.provider }
                .distinct()
                .singleOrNull()

    /**
     * Ask each embedding resolver in turn what the role means, and materialize the answer.
     *
     * Null when nothing answered, or when the answer named a model this deployment cannot serve.
     * Callers decide what that means: an explicitly requested role throws, the default falls back
     * to the placeholder.
     */
    private fun attemptEmbeddingRole(role: String, context: ModelSelectionContext): EmbeddingService? =
        when (val resolution = embeddingRoleResolvers.firstNotNullOfOrNull { it.resolve(role, context) }) {
            is EmbeddingRoleResolution.Service -> resolution.embeddingService

            // By name alone, placeholder included. A deployment may point a role AT the placeholder
            // deliberately - `memory: setup-required-embedding` says "this role has no model yet"
            // - and screening placeholders out here would refuse the one configuration that means
            // to name one. A role naming a model nothing registered still resolves to nothing,
            // because the name simply does not match.
            is EmbeddingRoleResolution.Model ->
                embeddingServices.firstOrNull { it.name == resolution.model }

            is EmbeddingRoleResolution.Credential -> embeddingFromCredential(role, resolution.credential)

            null -> null
        }

    /**
     * What an explicitly requested embedding role does when nothing satisfied it: it throws, in
     * every deployment, keyed or not.
     *
     * DELIBERATELY UNLIKE [resolveRole], which hands an unsatisfied chat role the placeholder when
     * a key has yet to arrive. An embedding model is a schema commitment - a vector index is
     * created AT its width - so there is nothing a placeholder can stand in for, and handing one
     * back would turn "this role names no model I can serve" into a service whose
     * [EmbeddingService.awaitingProviderKey] invites the caller to skip silently. The asymmetry is
     * stated where the fallback is defined, in [placeholderEmbeddingService] and beside the
     * startup validation, and this is the third place it has to hold.
     *
     * The DEFAULT is a different question and keeps its placeholder: a caller asking for whatever
     * the deployment has is entitled to be told, by a service that says so, that it has nothing.
     */
    private fun embeddingRoleFallback(criteria: ByRoleModelSelectionCriteria): EmbeddingService {
        logger.warn("No embedding service available for role '{}'", criteria.role)
        throw NoSuitableModelException.forModels(criteria, embeddingServices)
    }

    /**
     * Build - or reuse - an embedding service for the model this role names under the user's own
     * provider.
     *
     * The lookup ends at the flat map, which [ConfigurableEmbeddingRoleResolver.resolve] refuses
     * to read under a user key - not a contradiction, because the two are spending different
     * money. There, reading it would serve a service the DEPLOYMENT is keyed for and billed for.
     * Here, only the model NAME is taken and the service is built on the user's own key, so the
     * user pays for the model their role names. Reachable only from an application resolver
     * returning [EmbeddingRoleResolution.Credential], since the configured one gets this far only
     * when the nested map already answered.
     */
    private fun embeddingFromCredential(role: String, credential: ProviderCredential): EmbeddingService? {
        val model = configurableEmbeddingRoleResolver.configuredModelFor(role, credential.provider)
        if (model == null) {
            logger.warn(
                "Embedding role '{}' has no model configured for provider '{}' under embabel.models.embedding-roles",
                role, credential.provider,
            )
            return null
        }
        val service = credentialEmbeddingService(credential, model)
        if (service == null) {
            logger.warn(
                "Nothing built an embedding service for provider '{}', needed for role '{}'. Register a CredentialEmbeddingServiceFactory for it, and check that the module speaking its wire protocol is on the classpath",
                credential.provider, role,
            )
        }
        return service
    }

    /**
     * The cached service for this (provider, key, model), or one freshly built and cached.
     *
     * Read then put rather than computeIfAbsent, for the reason spelled out in the LLM
     * counterpart: building can reach the provider to observe the model's width, and holding the
     * map's lock across that would block every unrelated lookup for every other user. A race costs
     * one redundant build and never a wrong service.
     *
     * Shared by the two ways a credential reaches a model - a role that named it, and a caller
     * that named it - so the cache is one cache and the locking argument is made once.
     */
    private fun credentialEmbeddingService(credential: ProviderCredential, model: String): EmbeddingService? {
        val key = CredentialModelKey.of(credential, model)
        return credentialEmbeddingServices[key]
            ?: credentialEmbeddingServiceFactories
                .firstNotNullOfOrNull { it.createEmbeddingService(credential, model) }
                ?.also { credentialEmbeddingServices[key] = it }
    }

    /**
     * The registered embedding placeholder, if this deployment carries one.
     *
     * Structural rather than by name, like [placeholderLlm]: `com.embabel.agent.spi` owns the
     * marker and this class must not depend on the BYOK module that implements it.
     *
     * Note what falling back does NOT do. The placeholder cannot embed and will not report a
     * dimension, so every consumer that reaches it still fails - deliberately, because an
     * embedding model is a schema commitment and nothing can stand in for one. What the fallback
     * buys is that the deployment STARTS: a consumer resolving the default embedding service while
     * its beans are being created no longer takes down the context before any BYOK code can run.
     * Consumers that provision a vector index should ask [EmbeddingService.awaitingProviderKey] and skip
     * until a real model is registered - the property, not a type test, because it survives
     * wrapping.
     */
    /**
     * A chat service for a model a local runner is serving under this exact name, or null.
     *
     * The by-NAME counterpart of what [LocalModelRoleResolver] does for a role, and the reason
     * [listModelNames] can name a model that arrived after startup without lying: everything the
     * platform lists is something a caller can then ask for. Catalogs are tried in bean order, and
     * the first that is serving the name answers - two runners serving the same model name serve
     * the same model, so there is nothing to choose between them.
     */
    private fun locallyServedLlm(name: String): LlmService<*>? =
        localModelCatalogs.firstNotNullOfOrNull { it.llmNamed(name) }

    /** The embedding counterpart of [locallyServedLlm]. */
    private fun locallyServedEmbedding(name: String): EmbeddingService? =
        localModelCatalogs.firstNotNullOfOrNull { it.embeddingNamed(name) }

    /** Names a local runner is serving of this kind, for the listings. */
    private fun locallyServedNames(kind: LocalModelKind): List<String> =
        localModelCatalogs.flatMap { it.servedNames(kind) }.distinct()

    private fun placeholderEmbeddingService(warn: Boolean): EmbeddingService? =
        embeddingServices.firstOrNull { it.awaitingProviderKey }
            ?.also { if (warn) reportEmbeddingFallback(it) }

    /**
     * Why the default fell back to the placeholder. Three situations reach that line and only one
     * of them is about a key.
     */
    private enum class EmbeddingFallbackReason {
        /** `default-embedding-model` is unset or empty: a choice not yet made. */
        UNSET,

        /** It names a role, which nothing has satisfied yet. The expected state before a key. */
        ROLE,

        /** It names a model, and nothing registered one under that name. A typo, or a missing module. */
        UNREGISTERED,
    }

    /**
     * The last fallback situation reported, so a standing condition is stated once rather than per call.
     *
     * The default is resolved on EVERY call that does not name a role, and until a key arrives it
     * falls back every time. Reporting each one turns the single line an operator has to read into
     * a line per embedded chunk, which is how a real warning gets filtered out. Keyed by the reason
     * AND the configured name, so a genuine change - configuration re-bound, a key arriving and
     * later going away - is reported again rather than swallowed. Read-modify-write in one step, so
     * concurrent first calls produce one line rather than one each.
     */
    private val lastReportedEmbeddingFallback = AtomicReference<String?>(null)

    /**
     * Say WHY the default fell back to the placeholder, in the terms the reader has to act on.
     *
     * Collapsing the three reasons - which is what a single "no provider key" message did - sends
     * somebody whose key is present and working to go and look for a key, and the subsystem they
     * then debug is the one that is already right. Observed on an appliance that had registered
     * four chat models from a live key and reported `This deployment holds no provider API key` on
     * every embedding call.
     *
     * Said once per situation, for the reason [lastReportedEmbeddingFallback] gives.
     */
    private fun reportEmbeddingFallback(placeholder: EmbeddingService) {
        val configured = properties.defaultEmbeddingModel
        val reason = when {
            configured.isNullOrBlank() -> EmbeddingFallbackReason.UNSET
            properties.defaultEmbeddingModelNamesRole() -> EmbeddingFallbackReason.ROLE
            else -> EmbeddingFallbackReason.UNREGISTERED
        }
        val situation = "$reason:$configured"
        if (lastReportedEmbeddingFallback.getAndSet(situation) == situation) {
            return
        }
        when (reason) {
            EmbeddingFallbackReason.UNSET -> logger.warn(
                """
                No embedding model is configured: 'embabel.models.default-embedding-model' is unset or empty, so the '{}' placeholder stands in.
                This is a CHOICE not yet made, not a missing key - set it to one of the registered services, or to a role. Available: {}
                """.trimIndent(),
                placeholder.name, embeddingServices.map { it.name },
            )

            EmbeddingFallbackReason.ROLE -> logger.info(
                """
                Default embedding service '{}' is a role, and will be resolved per call.
                Until a key is supplied it falls back to the '{}' placeholder
                """.trimIndent(),
                configured, placeholder.name,
            )

            EmbeddingFallbackReason.UNREGISTERED -> logger.warn(
                """
                Default embedding service '{}' is not registered; falling back to the '{}' placeholder.
                Embedding will fail with an actionable 'no embedding service configured' error until a key is supplied. Available: {}
                """.trimIndent(),
                configured, placeholder.name, embeddingServices.map { it.name },
            )
        }
    }

    /**
     * Whether this deployment is waiting for an EMBEDDING key, the counterpart of [setupRequired].
     *
     * Separate, because the two halves are configured independently: an application can hold a
     * server-side chat key while embedding keys arrive per user, or the reverse. Deriving the
     * embedding gate from [setupRequired] made a deployment with a real LLM fail its context
     * refresh on an embedding role it has no key for yet - exactly the startup failure the
     * placeholder exists to remove, reappearing in the mixed configuration.
     */
    private val embeddingSetupRequired: Boolean =
        resolveDefaultEmbeddingService(warnOnFallback = false)?.awaitingProviderKey == true

    init {
        require(properties.credentialServiceCacheSize >= 1) {
            """
            embabel.models.credential-service-cache-size must be at least 1, was ${properties.credentialServiceCacheSize}.
            Zero or negative evicts every entry on insert, so nothing is ever cached and every call rebuilds a service.
            """.trimIndent()
        }
        properties.llms.forEach { (role, model) ->
            if (llms.none { it.name == model }) {
                // Fatal, unless this deployment is waiting for a key. A name that resolves to
                // nothing is a typo in a deployment that has one, and letting it start would move
                // the failure to whichever unrelated call first asks for that role. A deployment in
                // setup-required mode has no models registered yet by definition, so the same name
                // is expected there and only worth reporting.
                if (setupRequired) {
                    logger.warn(
                        """
                        LLM '{}' for role '{}' is not registered. This deployment is awaiting a key, so that is expected;
                        the role will report 'no LLM configured' until one is supplied. Available: {}
                        """.trimIndent(),
                        model, role, llms.map { it.name },
                    )
                } else {
                    error("LLM '$model' for role $role is not available: Choices are ${llms.map { it.name }}")
                }
            }
        }
        checkNestedRoles()
        logger.info(infoString(verbose = true))

        properties.embeddingServices.forEach { (role, model) ->
            if (embeddingServices.none { it.name == model }) {
                /*
                 * The same gate as the LLM roles above, and for the same reason: an unresolvable
                 * name is a typo in a deployment that holds a key, and expected in one still
                 * waiting for it. There is no fallback here, though, and there should not be -
                 * an embedding model is a schema commitment and nothing can stand in for one.
                 * The gate decides only whether the deployment STARTS; asking for the service
                 * still throws.
                 *
                 * Either gate opens it. [setupRequired] alone was not enough: the two halves are
                 * configured independently, so a deployment holding a chat key while embedding keys
                 * arrive at runtime is awaiting one here and was failing to start. [setupRequired]
                 * still counts on its own, because a deployment awaiting a chat key has registered
                 * nothing at all yet, embedding services included.
                 */
                if (setupRequired || embeddingSetupRequired) {
                    logger.warn(
                        """
                        Embedding model '{}' for role '{}' is not registered. This deployment is awaiting a key,
                        so that is expected; asking for that role will still fail. Available: {}
                        """.trimIndent(),
                        model, role, embeddingServices.map { it.name },
                    )
                } else {
                    error("Embedding model '$model' for role $role is not available: Choices are ${embeddingServices.map { it.name }}")
                }
            }
        }
        /*
         * The nested map is NOT checked the way the flat one is, matching how `roles` is treated
         * for chat. An entry there names a model under a provider this deployment may hold no key
         * for - that is what the shape is FOR - so an unregistered name is expected rather than a
         * typo, and failing on it would stop exactly the bring-your-own-key deployment the map
         * exists to serve. Report it and carry on.
         */
        properties.embeddingRoles.forEach { (role, byProvider) ->
            byProvider.forEach { (provider, model) ->
                if (embeddingServices.none { it.name == model }) {
                    logger.info(
                        "Embedding model '{}' for role '{}' under provider '{}' is not registered here; it will be built from a key supplied at runtime",
                        model, role, provider,
                    )
                }
            }
        }
    }

    /**
     * Warn about `embabel.models.roles` entries that cannot be satisfied, on the same terms as
     * the flat map above.
     *
     * Only entries for the deployment's own provider are checked. An entry for a provider this
     * deployment is not keyed for is the point of the nested shape - it applies when a user
     * brings a key for that provider, and its model is not expected to be registered here, so
     * warning about it would train people to ignore the warning.
     *
     * A provider that has declared [LateArrivingModels] is excused for the same reason one step
     * later: its models are pulled on the host, so a role naming one nobody has pulled yet is the
     * ordinary state of an appliance before setup, and refusing to start is refusing to reach the
     * point where the operator could pull it.
     *
     * Without this, a typo under `roles` is silent until something asks for the role: the entry
     * is found, its model is not registered, and resolution throws rather than falling back to
     * the flat map - which is the one case where the nested shape can take a role AWAY.
     */
    private fun checkNestedRoles() {
        val deploymentProvider = defaultLlm.provider
        properties.roles.forEach { (role, byProvider) ->
            byProvider
                .filterKeys { it.equals(deploymentProvider, ignoreCase = true) }
                .forEach { (provider, options) ->
                    val model = options.modelName
                    if (provider.lowercase() in lateArrivingProviders) {
                        // Reported, never silent: the entry has been EXCUSED from a check that would
                        // otherwise have stopped the deployment, and an operator reading a boot log
                        // to work out why a role does nothing needs to see that.
                        if (model == null || llms.none { it.name == model }) {
                            logger.info(
                                "Role '{}' names model '{}' under provider '{}', whose models may arrive after startup; it will resolve once that model is served",
                                role, model ?: "<unspecified>", provider,
                            )
                        }
                        return@forEach
                    }
                    if (model == null) {
                        reportUnsatisfiableRole(
                            "Role '$role' under provider '$provider' names no model, so anything asking for that role will fail",
                        )
                    } else if (llms.none { it.name == model }) {
                        reportUnsatisfiableRole(
                            """
                            LLM '$model' for role '$role' under provider '$provider' - this deployment's own provider - is not available. Available: ${llms.map { it.name }}
                            """.trimIndent(),
                        )
                    }
                }
        }
    }

    /**
     * Report a role this deployment cannot satisfy, on the same terms as the flat map: fatal in a
     * deployment that holds a key, expected in one that is waiting for one.
     *
     * Shared so the two shapes cannot drift. Applying the rule to only one of them was the original
     * defect here - a typo under `roles` warned and booted while the same typo under `llms` did not.
     */
    private fun reportUnsatisfiableRole(message: String) {
        if (setupRequired) {
            logger.warn("{}. This deployment is awaiting a key, so that is expected", message)
        } else {
            error(message)
        }
    }

    private fun showModel(model: LlmService<*>): String {
        val roles = properties.llms.filter { it.value == model.name }.keys
        val maybeRoles = if (roles.isNotEmpty()) " - Roles: ${roles.joinToString(", ")}" else ""
        return "name: ${model.name}, provider: ${model.provider}$maybeRoles"
    }

    private fun showEmbeddingModel(model: EmbeddingService): String {
        val roles = properties.embeddingServices.filter { it.value == model.name }.keys
        val maybeRoles = if (roles.isNotEmpty()) " - Roles: ${roles.joinToString(", ")}" else ""
        return "name: ${model.name}, provider: ${model.provider}$maybeRoles"
    }

    override fun listModels(): List<ModelMetadata> =
        llms.map {
            LlmMetadata(
                it.name,
                provider = it.provider,
                knowledgeCutoffDate = it.knowledgeCutoffDate,
                pricingModel = it.pricingModel,
            )
        } + embeddingServices.map {
            EmbeddingServiceMetadata(
                it.name,
                provider = it.provider,
                pricingModel = it.pricingModel,
            )
        } + localModelMetadata()

    /**
     * Metadata for models a local runner is serving that nothing registered at startup.
     *
     * Name and provider only. The rest of a model's metadata is read off a BUILT service, and
     * building one per entry just to list it would turn a listing into a round of work against the
     * runner - so what is not known is left null rather than guessed. Anything already registered
     * is skipped, so a model that was there at boot is described once, by its own service.
     */
    private fun localModelMetadata(): List<ModelMetadata> =
        localModelCatalogs.flatMap { catalog ->
            // Chat models the runner serves, minus those registered at startup (already listed).
            catalog.servedNames(LocalModelKind.CHAT)
                .filter { name -> llms.none { it.name == name } }
                .map { LlmMetadata(it, provider = catalog.provider) } +
                // The same for embedding models, checked against the registered embedding services.
                catalog.servedNames(LocalModelKind.EMBEDDING)
                    .filter { name -> embeddingServices.none { it.name == name } }
                    .map { EmbeddingServiceMetadata(it, provider = catalog.provider) }
        }


    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val llmsInfo = "Available LLMs:\n\t${
            llms
                .sortedBy { it.name }
                .joinToString("\n\t") { showModel(it) }
        }"
        val embeddingServicesInfo =
            "Available embedding services:\n\t${
                embeddingServices
                    .sortedBy { it.name }
                    .joinToString("\n\t") { showEmbeddingModel(it) }
            }"
        return "Default LLM: ${properties.defaultLlm}\n$llmsInfo\nDefault embedding service: ${properties.defaultEmbeddingModel}\n$embeddingServicesInfo".indent(
            indent
        )
    }

    override fun resolveLlmOptions(llmOptions: LlmOptions): LlmOptions {
        val criteria = llmOptions.criteria
        if (criteria !is ByRoleModelSelectionCriteria) {
            return llmOptions
        }
        val resolved = resolveRole(criteria.role, ModelSelectionContextHolder.get())
        return llmOptions
            .withDefaultsFrom(resolved.llmOptions)
            .copy(
                modelSelectionCriteria = ModelSelectionCriteria.preResolved(resolved.llmService),
                // Keep the role that was asked for. Selection no longer consults it - the
                // pre-resolved criteria decide - but events, logs and cost attribution all want to
                // know a call was made "as cheapest", which is otherwise lost the moment it resolves.
                role = criteria.role,
            )
    }

    override fun configuredOptionsForRole(role: String): LlmOptions? =
        configurableRoleResolver.configuredOptionsFor(role, defaultLlm.provider)

    /**
     * Ask each resolver in turn what the role means, and materialize the answer.
     *
     * A role that cannot be satisfied throws, rather than quietly resolving to something else.
     * Falling back to the default LLM would mean a role like "cheapest" silently becoming the most
     * capable - and most expensive - model in the deployment, which is exactly the kind of thing
     * nobody notices until the bill arrives.
     *
     * Booting is a separate question: an unsatisfiable role only warns at startup, so a deployment
     * keyed for one provider still starts and still serves every role that does work.
     */
    private fun resolveRole(role: String, context: ModelSelectionContext): ResolvedRole {
        val attempt = attemptRole(role, context)
        val resolved = attempt.resolved
        if (resolved != null) {
            return resolved
        }
        val resolution = attempt.resolution
        if (setupRequired) {
            // No key has arrived yet, so no role can name a registered model and this is not a
            // misconfiguration. Hand back the placeholder rather than throwing: the caller then
            // fails with the same actionable "no LLM configured" error that the default LLM already
            // gives, instead of a NoSuitableModelException listing the placeholder as a choice.
            //
            // Not a silent substitution of the kind this method otherwise refuses. The objection to
            // falling back is that "cheapest" would quietly become a real, expensive model; the
            // placeholder answers nothing and bills nothing.
            //
            // The placeholder is told which role it is standing in for, so the eventual failure can
            // name the role and the model it wanted. That is the only thing separating a typo from
            // a key that has not arrived, and at this point it is the last chance to say it: both
            // look identical at startup, and after this the caller sees only "no LLM configured".
            val wantedModel = wantedModelFor(role, resolution)
            logger.debug(
                "Role '{}' (model '{}') has no registered model and this deployment is awaiting a key; using the placeholder",
                role, wantedModel ?: "unspecified",
            )
            val placeholder = (defaultLlm as? PlaceholderLlmService)?.forUnsatisfiedRole(role, wantedModel)
            return ResolvedRole(placeholder ?: defaultLlm, LlmOptions.withDefaults())
        }
        logger.warn(
            "No model available for role '{}' (provider: {})",
            role, context.provider ?: "deployment default",
        )
        throw NoSuitableModelException(ByRoleModelSelectionCriteria(role), llms.map { it.name })
    }

    /**
     * Ask each resolver in turn what the role means, and materialize whatever answers - or nothing,
     * if no resolver answered or the answer named a model this deployment cannot serve.
     *
     * Split out of [resolveRole] because the deployment default resolves through the same chain but
     * must not inherit its ending: an unsatisfiable role throws, where an unsatisfiable default
     * falls back to what `default-llm` resolved to at startup.
     *
     * Carries the raw [RoleResolution] back alongside the result so a caller can report what was
     * wanted, which is otherwise lost the moment materialization fails.
     */
    private fun attemptRole(role: String, context: ModelSelectionContext): RoleAttempt {
        val resolution = roleResolvers.firstNotNullOfOrNull { it.resolve(role, context) }
        val resolved = when (resolution) {
            is RoleResolution.Service -> ResolvedRole(resolution.llmService, resolution.llmOptions)

            is RoleResolution.Options -> byName(resolution.llmOptions)
                ?.let { ResolvedRole(it, resolution.llmOptions) }

            is RoleResolution.Credential -> fromCredential(role, resolution.credential)

            null -> null
        }
        return RoleAttempt(resolution, resolved)
    }

    /**
     * What `default-llm` means for this call.
     *
     * A name that matched a registered service at startup is that service, resolved once and never
     * sent through the resolvers - so the ordinary deployment pays nothing for this and cannot have
     * its default overridden per call.
     *
     * Anything else goes through the role chain, on every call. That is the point: under BYOK the
     * first key arrives after startup, so a default fixed at construction can only be the
     * placeholder, permanently, until the process restarts. Resolving per call means the same key
     * that satisfies every other role satisfies the default too.
     *
     * A name that is neither a role nor a registered model reaches the chain as well, rather than
     * being refused for not looking like a role. Resolvers answer per call and per user; whether a
     * name is "a role" is not something this class can decide on their behalf.
     *
     * Falls back to [defaultLlm] - the placeholder, in the deployment this exists for - when nothing
     * answers, rather than throwing as an unsatisfied role does. A caller asking for the default
     * asked for whatever this deployment has, and the placeholder's "no LLM configured" is the
     * accurate answer; a [NoSuitableModelException] naming the default as an unsatisfiable role
     * would not be.
     */
    private fun defaultLlmService(): LlmService<*> {
        registeredDefaultLlm?.let { return it }
        val resolved = attemptRole(properties.defaultLlm, ModelSelectionContextHolder.get()).resolved?.llmService
        // A default naming a MODEL a runner has since started serving, for the same reason the
        // embedding default asks: the name was unresolvable at startup and is not any more. Guarded
        // on the name not being a ROLE, as the embedding default is: a role resolves through the
        // chain, which already includes the local resolvers, so asking the runners for a model
        // called `cheapest` can only miss - and a miss is an HTTP request, on the path of every
        // call a BYOK deployment makes before a key arrives.
            ?: properties.defaultLlm.takeUnless { properties.defaultLlmNamesRole() }?.let { locallyServedLlm(it) }
        if (resolved == null) {
            // Debug, not warn: under BYOK this is the ordinary state of every call made before a
            // key arrives, and the eventual failure already names it.
            logger.debug(
                "Nothing resolved default LLM '{}' for this call; falling back to '{}'",
                properties.defaultLlm, defaultLlm.name,
            )
        }
        return resolved ?: defaultLlm
    }

    /**
     * A resolution attempt: what the resolvers said, and what it materialized into, if anything.
     */
    private data class RoleAttempt(
        val resolution: RoleResolution?,
        val resolved: ResolvedRole?,
    )

    /**
     * The model name [role] carried, taken from whatever answered for it.
     *
     * Read back off the resolution rather than re-queried, so it is the name resolution actually
     * tried and not a second, possibly different, lookup. A [RoleResolution.Credential] carries a
     * key rather than a model, so that one case does have to ask - the same call
     * [fromCredential] made.
     *
     * Null when nothing named a model: no resolver answered at all, or the entry that did names
     * only a provider. There is then nothing to report beyond the role itself.
     */
    private fun wantedModelFor(role: String, resolution: RoleResolution?): String? =
        when (resolution) {
            is RoleResolution.Options -> resolution.llmOptions.modelName
            is RoleResolution.Credential ->
                configurableRoleResolver.optionsFor(role, resolution.credential.provider)?.modelName
            // A Service resolved to a built service, so it never reaches the placeholder.
            is RoleResolution.Service, null -> null
        }

    /**
     * Build - or reuse - a service for the model this role names under the user's own provider.
     */
    private fun fromCredential(
        role: String,
        credential: ProviderCredential,
    ): ResolvedRole? {
        val options = configurableRoleResolver.optionsFor(role, credential.provider)
        val model = options?.modelName
        if (model == null) {
            logger.warn(
                "Role '{}' has no model configured for provider '{}' under embabel.models.roles",
                role, credential.provider,
            )
            return null
        }
        // Read then put rather than computeIfAbsent: building a service can validate the key over
        // the network, and computeIfAbsent would hold the map's lock for the duration. A race here
        // costs one redundant build, never a wrong service.
        //
        // Concretely. Two requests for the same user arrive together, both resolving "cheapest"
        // under the same key, and neither finds a cached entry. With computeIfAbsent, the first
        // holds the map's lock across a network round trip to validate the key, and the second
        // blocks on it - as does every unrelated lookup for every other user, because it is one
        // map. Reading first, both build a service, both put, and the second put wins. The key
        // and model are identical, so the two services are interchangeable; the loser is dropped
        // and the cost is one wasted validation call.
        val key = CredentialModelKey.of(credential, model)
        val llmService = credentialLlmServices[key]
            ?: credentialLlmServiceFactories
                .firstNotNullOfOrNull { it.createLlmService(credential, model) }
                ?.also { credentialLlmServices[key] = it }
        if (llmService == null) {
            logger.warn(
                """Nothing built a service for provider '{}', needed for role '{}'. Register a CredentialEndpointResolver for it, and check that the module speaking its wire protocol is on the classpath""",
                credential.provider, role,
            )
            return null
        }
        return ResolvedRole(llmService, options)
    }

    /**
     * The registered service named by these options, or null if it names none.
     */
    private fun byName(llmOptions: LlmOptions): LlmService<*>? =
        llmOptions.modelName?.let { name -> llms.firstOrNull { it.name == name } }

    /**
     * The chat service this deployment can serve under [name], registered or locally served.
     *
     * Registered first, always: a model captured at startup is the one the deployment was built
     * with, and a runner that happens to serve a model of the same name must not displace it.
     */
    private fun llmNamed(name: String): LlmService<*>? =
        llms.firstOrNull { it.name == name } ?: locallyServedLlm(name)

    /**
     * Every chat model name a caller could ask for, for the "available" list in a failure.
     *
     * The same set [listModelNames] reports, so being told what is available and then asking for
     * one of them cannot disagree.
     */
    private fun listableLlmNames(): List<String> =
        (llms.map { it.name } + locallyServedNames(LocalModelKind.CHAT)).distinct()

    override fun listRoles(modelClass: Class<*>): List<String> {
        return when {
            LlmService::class.java.isAssignableFrom(modelClass) ->
                (properties.llms.keys + properties.roles.keys).toList()
            EmbeddingService::class.java.isAssignableFrom(modelClass) -> properties.embeddingServices.keys.toList()
            else -> throw IllegalArgumentException("Unsupported model class: $modelClass")
        }
    }

    /**
     * Every model of this class the deployment can serve - registered at startup, or being served
     * by a local runner since.
     *
     * A late model is listed as well as usable, because a listing is what an operator picks from:
     * one that showed only what was captured at boot would leave a freshly pulled model invisible
     * to the very UI meant to select it, and "pull it and it is there" is the whole point. Every
     * name here answers to [getLlm] / [getEmbeddingService] by name.
     *
     * The local part is as fresh as the catalogs are, so a model pulled seconds ago can be usable
     * BY NAME before it is listed: a by-name lookup has a name to miss on and re-asks the runner,
     * while a listing has nothing to miss on and is served from the snapshot. The gap is at most
     * [LocalModelDiscoveryProperties.refreshInterval], and closing it would mean an HTTP round trip
     * per listing - the cost the snapshot exists to avoid.
     */
    override fun listModelNames(modelClass: Class<*>): List<String> {
        return when {
            LlmService::class.java.isAssignableFrom(modelClass) -> listableLlmNames()
            EmbeddingService::class.java.isAssignableFrom(modelClass) ->
                (embeddingServices.map { it.name } + locallyServedNames(LocalModelKind.EMBEDDING)).distinct()

            else -> throw IllegalArgumentException("Unsupported model class: $modelClass")
        }
    }

    override fun getLlm(criteria: ModelSelectionCriteria): LlmService<*> =
        when (criteria) {
            is ByRoleModelSelectionCriteria -> {
                resolveRole(criteria.role, ModelSelectionContextHolder.get()).llmService
            }

            is ByNameModelSelectionCriteria -> {
                llmNamed(criteria.name) ?: throw NoSuitableModelException(criteria, listableLlmNames())
            }

            is RandomByNameModelSelectionCriteria -> {
                val models = criteria.names.mapNotNull { llmNamed(it) }
                if (models.isEmpty()) {
                    throw NoSuitableModelException(criteria, listableLlmNames())
                }
                models.random()
            }

            is FallbackByNameModelSelectionCriteria -> {
                var llm: LlmService<*>? = null
                for (requestedName in criteria.names) {
                    llm = llmNamed(requestedName)
                    if (llm != null) {
                        break
                    } else {
                        logger.info("Requested LLM '{}' not found", requestedName)
                    }
                }
                llm
                    ?: throw NoSuitableModelException(criteria, listableLlmNames())
            }

            is AutoModelSelectionCriteria -> {
                // The infrastructure above this class should have resolved this
                error("Auto model selection criteria should have been resolved upstream")
            }

            is DefaultModelSelectionCriteria -> {
                defaultLlmService()
            }

            is PreResolvedModelSelectionCriteria<*> -> {
                @Suppress("UNCHECKED_CAST")
                criteria.resolved as LlmService<*>
            }
        }

    /**
     * EXHAUSTIVE over [ModelSelectionCriteria], deliberately and with no `else`, exactly as
     * [getLlm] has always been.
     *
     * The `else` that used to sit here is how a named model became the deployment's default for
     * sixteen months: it is a compile-time permission slip never to think about embeddings again.
     * `getLlm` breaks when a criteria type is added; this method absorbed it, which is why
     * [PreResolvedModelSelectionCriteria] - added later - fell straight into the hole. Adding a
     * criteria type must now break BOTH, and that is the point.
     *
     * One context read for the whole call. The resolvers answer per role and per name, and every
     * arm asking the holder separately is the asymmetry that makes propagation bugs hard to see.
     */
    override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService {
        val context = ModelSelectionContextHolder.get()
        return when (criteria) {
            // Per call, through the resolver chain - so a key stored after boot satisfies this
            // role on the next request rather than the next restart.
            is ByRoleModelSelectionCriteria ->
                attemptEmbeddingRole(criteria.role, context) ?: embeddingRoleFallback(criteria)

            /*
             * A NAME IS NOT THE DEFAULT, and falling through to it was the whole bug. Asking for
             * a large model returned whatever the deployment's default happened to be, with
             * nothing to say the request had been ignored - so an application offering "change
             * the embedding model" reported success, re-embedded its entire corpus, and left the
             * model exactly as it was. Observed on an appliance: previousModel and newModel came
             * back identical for a change between two different models.
             */
            is ByNameModelSelectionCriteria ->
                attemptEmbeddingName(criteria.name, context) ?: embeddingNameFallback(criteria)

            // Uniform over the names this deployment can actually serve, which is what
            // getLlm's filter-then-random does one step earlier.
            is RandomByNameModelSelectionCriteria ->
                criteria.names.shuffled()
                    .firstNotNullOfOrNull { attemptEmbeddingName(it, context) }
                    ?: embeddingNameFallback(criteria)

            // First name that resolves, each miss logged - the same shape, and the same INFO
            // level, as the LLM counterpart. A miss here is expected traffic, not a fault.
            is FallbackByNameModelSelectionCriteria ->
                criteria.names
                    .firstNotNullOfOrNull { name ->
                        attemptEmbeddingName(name, context).also {
                            if (it == null) logger.info("Requested embedding model '{}' not available", name)
                        }
                    }
                    ?: embeddingNameFallback(criteria)

            /*
             * The caller already holds the service, so there is nothing to resolve - and nothing
             * this class may substitute. Under BYOK the resolved service was built on the USER's
             * key: handing back the deployment default, which is what the `else` did, embeds on
             * the deployment's key and bills the deployment, while reporting success.
             *
             * Checked rather than unchecked, unlike [getLlm]. Criteria are Jackson-deserializable
             * and generic, so a wrong payload is reachable, and NoSuitableModelException names the
             * criteria where a ClassCastException names two erased types.
             */
            is PreResolvedModelSelectionCriteria<*> ->
                criteria.resolved as? EmbeddingService ?: embeddingNameFallback(criteria)

            /*
             * The default, which is what both mean.
             *
             * DELIBERATELY UNLIKE [getLlm], where AUTO is an error because the infrastructure
             * above resolves it first. Nothing analyses a prompt to pick a vector width, so for
             * embeddings AUTO has only ever meant "whatever this deployment has" - and that is a
             * service the caller can ask, via EmbeddingService.awaitingProviderKey, whether it is
             * real yet.
             */
            is AutoModelSelectionCriteria, is DefaultModelSelectionCriteria -> defaultEmbeddingService()
        }
    }

    /**
     * The embedding service a caller named, or null when this deployment cannot serve that name.
     *
     * Registered first, which is the whole answer for a deployment whose models come from its own
     * key at build time. A deployment whose key arrives later has none registered, so the name is
     * built from that key the same way a role is - the model is known here, only the credential is
     * missing, and the default role is where this deployment says which credential that is.
     *
     * A configured ROLE answers here too, because `default-embedding-model` takes one: an
     * application that reads the deployment's own default and asks for it back is asking by name
     * for a role, and the one value the configuration names would otherwise be the one value the
     * API rejected. Registered models win, and only a role
     * [ConfigurableModelProviderProperties.namesEmbeddingRole] declares is tried, so nothing a
     * caller meant as a model can be captured by a resolver.
     *
     * A placeholder never answers a NAME directly. A role may point at one deliberately - see
     * [attemptEmbeddingRole], where the caller asked for a role and a placeholder is a legitimate
     * "not yet" - but a caller naming a model is naming a width, and the placeholder has none.
     */
    private fun attemptEmbeddingName(name: String, context: ModelSelectionContext): EmbeddingService? =
        embeddingServices.firstOrNull { it.name == name && !it.awaitingProviderKey }
            ?: name.takeIf { properties.namesEmbeddingRole(it) }?.let { attemptEmbeddingRole(it, context) }
            // Ahead of the credential path: a model this machine is already serving costs nothing
            // and needs no key, so building the same model against a provider would be the more
            // expensive answer to a question already answered.
            ?: locallyServedEmbedding(name)
            ?: embeddingFromDeploymentCredential(name, context)

    /**
     * What a NAMED embedding model does when nothing can serve it: it throws, having said so.
     *
     * There is no honest substitute for a named embedding model. A vector index is created at ONE
     * model's width, so quietly serving another is how a corpus ends up holding two models'
     * vectors with nothing reporting it - and re-embedding is both the most expensive operation a
     * caller has and the only irreversible one. The same reasoning as [embeddingRoleFallback],
     * one criteria family over.
     */
    private fun embeddingNameFallback(criteria: ModelSelectionCriteria): Nothing {
        logger.warn(
            "No embedding service for {}. Registered: {}. Either register the model, or configure embabel.models.embedding-roles so the default embedding role names the credential it can be built from",
            criteria, embeddingServices.map { it.name },
        )
        throw NoSuitableModelException.forModels(criteria, embeddingServices)
    }

    /**
     * Build [model] from whatever provider key this deployment would use for its default embedding
     * role, or null when it has no such key.
     *
     * Asking the default role is how a credential is found without one being passed: the resolvers
     * answer per role, and the default role is the deployment's own statement of which key its
     * embeddings are made with. A deployment whose default is a plain model name has nothing to
     * ask, and correctly gets null.
     */
    private fun embeddingFromDeploymentCredential(model: String, context: ModelSelectionContext): EmbeddingService? {
        if (!properties.defaultEmbeddingModelNamesRole()) {
            logger.debug(
                "Embedding model '{}' is not registered, and the default embedding model names no role, so there is no credential to build it from",
                model,
            )
            return null
        }
        val role = properties.defaultEmbeddingModel ?: return null
        val credential = (embeddingRoleResolvers.firstNotNullOfOrNull { it.resolve(role, context) }
                as? EmbeddingRoleResolution.Credential)
            ?.credential
        if (credential == null) {
            logger.debug(
                "Embedding model '{}' is not registered, and the default embedding role '{}' resolved to no credential",
                model, role,
            )
            return null
        }
        return credentialEmbeddingService(credential, model)
            .also {
                if (it == null) {
                    logger.warn(
                        "Nothing built an embedding service for provider '{}', needed for the named model '{}'. Register a CredentialEmbeddingServiceFactory for it, and check that the module speaking its wire protocol is on the classpath",
                        credential.provider, model,
                    )
                }
            }
    }

    /**
     * A role, materialized: the service to call, and the options configured alongside it.
     */
    private data class ResolvedRole(
        val llmService: LlmService<*>,
        val llmOptions: LlmOptions,
    )

    /**
     * Cache key for a service built from a user's key.
     *
     * Identifies the key by SHA-256 rather than holding it, so the plaintext is not duplicated
     * into a map that lives as long as the platform does. The cached [LlmService] was built from
     * the key and still holds it, so this narrows the exposure rather than removing it - but it
     * removes the copy that exists purely for lookup, and keeps keys out of any heap dump taken
     * of the cache itself.
     *
     * A digest cannot collide in practice, and two users would have to share a provider AND a
     * model AND a SHA-256 collision to reach one another's service.
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
