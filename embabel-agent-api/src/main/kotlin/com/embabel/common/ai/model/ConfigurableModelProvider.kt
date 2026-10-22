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
import com.embabel.common.util.indent
import com.embabel.common.util.loggerFor
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import com.embabel.common.ai.model.local.LateArrivingModels
import com.embabel.common.ai.model.local.LocalModelCatalog
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties

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

    /** What `roles` and `llms` say the chat roles mean. */
    fun chatRoles(): ConfiguredRoles<LlmOptions> = ConfiguredRoles(
        byProvider = { roles },
        // A flat entry is only a model name, so it binds options that set the model and nothing else.
        flat = { llms.mapValues { (_, model) -> LlmOptions.withModel(model) } },
    )

    /** What `embedding-roles` and `embedding-services` say the embedding roles mean. */
    fun embeddingRoleBindings(): ConfiguredRoles<ModelName> = ConfiguredRoles(
        // Both maps hold plain model names; each one is wrapped as a ModelName binding.
        byProvider = { embeddingRoles.mapValues { (_, byProvider) -> byProvider.mapValues { (_, model) -> ModelName(model) } } },
        flat = { embeddingServices.mapValues { (_, model) -> ModelName(model) } },
    )
}

/**
 * Take LLM definitions from configuration
 *
 * Chat model selection is done by [ChatModelSelection] and embedding model selection by
 * [EmbeddingModelSelection]. Both use a [ModelFamily] for the steps that are the same for every kind
 * of model. This class builds the two selections, validates configuration at startup (chat first,
 * then embedding), and lists models and roles.
 */
class ConfigurableModelProvider @JvmOverloads constructor(
    private val llms: List<LlmService<*>>,
    private val embeddingServices: List<EmbeddingService>,
    private val properties: ConfigurableModelProviderProperties,
    roleResolvers: List<RoleResolver<*, *>> = emptyList(),
    modelPlatforms: List<ModelPlatform> = emptyList(),
    localModelCatalogs: List<LocalModelCatalog> = emptyList(),
) : ModelProvider {

    private val logger = loggerFor<ConfigurableModelProvider>()

    /**
     * Providers whose models may appear after startup, declared by the resolvers that cover them.
     *
     * Read by chat's nested-role check and nothing else. It is not part of resolution: what a role
     * means is the chain's business, and this only decides whether an unregistered name is a typo
     * worth refusing to start over. See [LateArrivingModels].
     *
     * Catalogs are read as well as resolvers, because a shipped runner's catalog is what knows
     * whether discovery is on - resolvers for it exist either way, so asking them would excuse a
     * provider that has been configured never to be asked.
     */
    private val lateArrivingProviders: Set<String> =
        (roleResolvers + localModelCatalogs)
            .filterIsInstance<LateArrivingModels>()
            .mapNotNull { it.lateArrivingProvider?.lowercase() }
            .toSet()

    private val chat = ChatModelSelection(
        llms = llms,
        properties = properties,
        applicationResolvers = roleResolvers.ofKind(LlmService.KIND),
        modelPlatforms = modelPlatforms,
        localModelCatalogs = localModelCatalogs,
        lateArrivingProviders = lateArrivingProviders,
    )

    private val embedding = EmbeddingModelSelection(
        embeddingServices = embeddingServices,
        properties = properties,
        applicationResolvers = roleResolvers.ofKind(EmbeddingService.KIND),
        modelPlatforms = modelPlatforms,
        localModelCatalogs = localModelCatalogs,
    )

    init {
        require(properties.credentialServiceCacheSize >= 1) {
            """
            embabel.models.credential-service-cache-size must be at least 1, was ${properties.credentialServiceCacheSize}.
            Zero or negative evicts every entry on insert, so nothing is ever cached and every call rebuilds a service.
            """.trimIndent()
        }
        chat.validate()
        warnOfUnselectedKinds(roleResolvers, modelPlatforms)
        logger.info(infoString(verbose = true))
        // Embedding is checked last so that a failed embedding check is thrown after the model
        // listing above has been logged. chat.setupRequired is set when chat is constructed, so it
        // does not depend on chat.validate() having run.
        embedding.validate(chatSetupRequired = chat.setupRequired)
    }

    /**
     * Warn about resolvers and model platforms that this provider never asks.
     *
     * A kind equals only itself, so a resolver holding any other kind instance is never asked.
     * That includes a new [ModelKind] created with the name "chat". Without this warning such a
     * bean would be ignored with nothing in the log.
     *
     * A platform is reported only when none of its kinds is selected. A platform that builds chat
     * and also a kind nothing selects yet is still asked for chat, so it is not reported.
     */
    private fun warnOfUnselectedKinds(
        roleResolvers: List<RoleResolver<*, *>>,
        modelPlatforms: List<ModelPlatform>,
    ) {
        val selectedKinds = setOf<ModelKind<*, *>>(LlmService.KIND, EmbeddingService.KIND)
        roleResolvers.filter { it.kind !in selectedKinds }.forEach {
            logger.warn(
                "Role resolver {} answers for {}, which this provider does not select, so it will never be asked. Use the kind's own constant, such as LlmService.KIND or EmbeddingService.KIND",
                it, it.kind,
            )
        }
        // Report a platform only if no kind it builds is selected; one selected kind means it is asked.
        modelPlatforms.filter { platform -> platform.kinds.none { it in selectedKinds } }.forEach {
            logger.warn(
                "Model platform {} builds {}, none of which this provider selects, so it will never be asked. Use the kind's own constant, such as LlmService.KIND or EmbeddingService.KIND",
                it, it.kinds,
            )
        }
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
        chat.family.unregisteredLocalNames().map { LlmMetadata(it.name, provider = it.provider) } +
            embedding.family.unregisteredLocalNames().map { EmbeddingServiceMetadata(it.name, provider = it.provider) }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val llmsInfo = "Available LLMs:\n\t${
            llms
                .sortedBy { it.name }
                .joinToString("\n\t") { chat.showModel(it) }
        }"
        val embeddingServicesInfo =
            "Available embedding services:\n\t${
                embeddingServices
                    .sortedBy { it.name }
                    .joinToString("\n\t") { embedding.showEmbeddingModel(it) }
            }"
        return "Default LLM: ${properties.defaultLlm}\n$llmsInfo\nDefault embedding service: ${properties.defaultEmbeddingModel}\n$embeddingServicesInfo".indent(
            indent
        )
    }

    override fun resolveLlmOptions(llmOptions: LlmOptions): LlmOptions = chat.resolveLlmOptions(llmOptions)

    override fun configuredOptionsForRole(role: String): LlmOptions? = chat.configuredOptionsForRole(role)

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
            LlmService::class.java.isAssignableFrom(modelClass) -> chat.listableLlmNames()
            EmbeddingService::class.java.isAssignableFrom(modelClass) -> embedding.listableNames()
            else -> throw IllegalArgumentException("Unsupported model class: $modelClass")
        }
    }

    override fun getLlm(criteria: ModelSelectionCriteria): LlmService<*> = chat.getLlm(criteria)

    override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService =
        embedding.getEmbeddingService(criteria)
}
