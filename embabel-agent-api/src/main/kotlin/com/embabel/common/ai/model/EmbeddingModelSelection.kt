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
import java.util.concurrent.atomic.AtomicReference

/**
 * How [ConfigurableModelProvider] selects embedding models: the deployment default, roles, names,
 * and what happens when nothing satisfies a request.
 *
 * The mechanics every kind shares - resolvers, credentials, local runners - are [family]'s. What is
 * here is embedding's own policy, which differs from chat's on purpose: an embedding model is a
 * schema commitment - a vector index is created at ONE model's width - so an explicitly requested
 * role or name never falls back to the placeholder, while the default does, so the deployment can
 * start before a key arrives.
 *
 * @param embeddingServices the embedding models registered at startup
 * @param properties the bound `embabel.models` configuration
 * @param applicationResolvers the application's embedding role resolvers, in order
 * @param modelPlatforms platforms that build models from a user's key; asked for embeddings
 * @param localModelCatalogs what local runners are serving
 */
internal class EmbeddingModelSelection(
    private val embeddingServices: List<EmbeddingService>,
    private val properties: ConfigurableModelProviderProperties,
    applicationResolvers: List<RoleResolver<EmbeddingService, ModelName>>,
    modelPlatforms: List<ModelPlatform>,
    localModelCatalogs: List<LocalModelCatalog>,
) {

    private val logger = loggerFor<ConfigurableModelProvider>()

    /**
     * Application resolvers first, the configuration-driven one last - the same ordering as chat,
     * and a separate chain because the kinds are separate: a resolver that answers "cheapest" for
     * chat must not be asked what it means for a vector.
     */
    val family: ModelFamily<EmbeddingService, ModelName> = ModelFamily(
        kind = EmbeddingService.KIND,
        registered = embeddingServices,
        roles = properties.embeddingRoleBindings(),
        resolvers = applicationResolvers +
            ConfigurableRoleResolver(EmbeddingService.KIND, properties.embeddingRoleBindings()) {
                defaultEmbeddingProviderName()
            },
        modelPlatforms = modelPlatforms,
        catalogs = localModelCatalogs,
        credentialCacheSize = properties.credentialServiceCacheSize,
        rolesProperty = "embabel.models.embedding-roles",
        isPlaceholder = { it.awaitingProviderKey },
    )

    /** The deployment default, computed per call as embedding services may not be available. */
    private fun defaultEmbeddingService() =
        resolveDefaultEmbeddingService(warnOnFallback = true)
            ?: throw IllegalArgumentException("Default embedding service '${properties.defaultEmbeddingModel}' not found in available models: ${embeddingServices.map { it.name }}")

    /**
     * The registered service `default-embedding-model` names directly, or null when it names a
     * role - or a model nothing has registered.
     *
     * The counterpart of [ChatModelSelection]'s registered default, and it plays the same part: a name that resolves
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
                family.locallyServed(model)?.let { return it }
            }
        }
        return placeholderEmbeddingService(warn = warnOnFallback)
    }

    /**
     * The provider behind the deployment's default embedding service, for
     * the embedding [ConfigurableRoleResolver] to pick a column with when no user key is active.
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
        family.materialize(role, family.resolve(role, context))?.model

    /**
     * What an explicitly requested embedding role does when nothing satisfied it: it throws, in
     * every deployment, keyed or not.
     *
     * DELIBERATELY UNLIKE chat's role resolution in [ChatModelSelection], which hands an unsatisfied chat role the placeholder when
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
     * The registered embedding placeholder, if this deployment carries one.
     *
     * Structural rather than by name, like chat's: `com.embabel.agent.spi` owns the
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
    private fun placeholderEmbeddingService(warn: Boolean): EmbeddingService? =
        family.placeholder()
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
     * Whether this deployment is waiting for an EMBEDDING key, the counterpart of [ChatModelSelection.setupRequired].
     *
     * Separate, because the two halves are configured independently: an application can hold a
     * server-side chat key while embedding keys arrive per user, or the reverse. Deriving the
     * embedding gate from [ChatModelSelection.setupRequired] made a deployment with a real LLM fail its context
     * refresh on an embedding role it has no key for yet - exactly the startup failure the
     * placeholder exists to remove, reappearing in the mixed configuration.
     */
    private val embeddingSetupRequired: Boolean =
        resolveDefaultEmbeddingService(warnOnFallback = false)?.awaitingProviderKey == true

    /**
     * Fail on a flat `embedding-services` entry this deployment cannot satisfy - unless it is
     * awaiting a key, when the same entry is expected and only reported.
     *
     * @param chatSetupRequired whether the deployment is awaiting a chat key
     */
    fun validate(chatSetupRequired: Boolean) {
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
                 * Either gate opens it. [chatSetupRequired] alone was not enough: the two halves are
                 * configured independently, so a deployment holding a chat key while embedding keys
                 * arrive at runtime is awaiting one here and was failing to start. [chatSetupRequired]
                 * still counts on its own, because a deployment awaiting a chat key has registered
                 * nothing at all yet, embedding services included.
                 */
                if (chatSetupRequired || embeddingSetupRequired) {
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

    fun showEmbeddingModel(model: EmbeddingService): String {
        val roles = properties.embeddingServices.filter { it.value == model.name }.keys
        val maybeRoles = if (roles.isNotEmpty()) " - Roles: ${roles.joinToString(", ")}" else ""
        return "name: ${model.name}, provider: ${model.provider}$maybeRoles"
    }

    /**
     * EXHAUSTIVE over [ModelSelectionCriteria], deliberately and with no `else`, exactly as
     * [ChatModelSelection.getLlm] has always been.
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
    fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService {
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
             * Checked rather than unchecked, unlike [ChatModelSelection.getLlm]. Criteria are Jackson-deserializable
             * and generic, so a wrong payload is reachable, and NoSuitableModelException names the
             * criteria where a ClassCastException names two erased types.
             */
            is PreResolvedModelSelectionCriteria<*> ->
                criteria.resolved as? EmbeddingService ?: embeddingNameFallback(criteria)

            /*
             * The default, which is what both mean.
             *
             * DELIBERATELY UNLIKE [ChatModelSelection.getLlm], where AUTO is an error because the infrastructure
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
            ?: family.locallyServed(name)
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
        val credential = (family.resolve(role, context) as? RoleResolution.Credential)
            ?.credential
        if (credential == null) {
            logger.debug(
                "Embedding model '{}' is not registered, and the default embedding role '{}' resolved to no credential",
                model, role,
            )
            return null
        }
        return family.credentialModel(credential, model)
            .also {
                if (it == null) {
                    logger.warn(
                        "Nothing built an embedding service for provider '{}', needed for the named model '{}'. Register a ModelPlatform that builds the embedding kind for it, and check that the module that calls that provider's API is on the classpath",
                        credential.provider, model,
                    )
                }
            }
    }

    /** Every embedding model name a caller could ask for. */
    fun listableNames(): List<String> = family.listableNames()
}
