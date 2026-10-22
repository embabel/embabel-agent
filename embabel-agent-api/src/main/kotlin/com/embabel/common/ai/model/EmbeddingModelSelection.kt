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
 * The steps every kind shares (resolvers, user keys, local runners) are done by [family]. This class
 * holds what embedding does differently from chat. A vector index is created for one model's vector
 * width, so when nothing satisfies a role or name the caller asks for, it throws rather than falling
 * back to the placeholder. When nothing satisfies the default embedding model, it falls back to the
 * placeholder, so the deployment can start before a key arrives.
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
     * The application's resolvers are asked first and the configuration-driven resolver last, in
     * the same order as chat. These resolvers are separate from the chat resolvers, so a resolver
     * that answers "cheapest" for chat is never asked about an embedding role.
     */
    val family: ModelFamily<EmbeddingService, ModelName> = ModelFamily(
        kind = EmbeddingService.KIND,
        registered = embeddingServices,
        roles = properties.embeddingRoleBindings(),
        // The lambda is called when a role is resolved, not when the resolver is built, so
        // `registeredDefaultEmbeddingService`, which is declared below this property, is set by
        // the time it is read.
        resolvers = applicationResolvers +
            ConfigurableRoleResolver(EmbeddingService.KIND, properties.embeddingRoleBindings()) {
                defaultEmbeddingProviderName()
            },
        modelPlatforms = modelPlatforms,
        catalogs = localModelCatalogs,
        credentialCacheSize = properties.credentialServiceCacheSize,
        rolesProperty = ROLES_PROPERTIES,
        isPlaceholder = { it.awaitingProviderKey },
    )

    /**
     * The deployment's default embedding service. It is resolved on each call, because the
     * embedding services may not be available at startup, unless `default-embedding-model` matched
     * a registered service at startup ([registeredDefaultEmbeddingService]). Throws when nothing
     * resolves and no placeholder is registered.
     */
    private fun defaultEmbeddingService() =
        resolveDefaultEmbeddingService(warnOnFallback = true)
            ?: throw IllegalArgumentException("Default embedding service '${properties.defaultEmbeddingModel}' not found in available models: ${embeddingServices.map { it.name }}")

    /**
     * The registered service whose name is `default-embedding-model`, or null when it names a role
     * or a model that is not registered.
     *
     * It works like the registered default in [ChatModelSelection]. A name that matches here is
     * found once, at startup. A deployment that has its key at startup then does no resolution per
     * call, and its default does not change between calls. When this is null, the default is
     * resolved on every call.
     */
    private val registeredDefaultEmbeddingService: EmbeddingService? =
        properties.defaultEmbeddingModel
            ?.takeIf { !properties.defaultEmbeddingModelNamesRole() }
            ?.let { name -> embeddingServices.firstOrNull { it.name == name } }

    /**
     * What `default-embedding-model` resolves to, or null if it resolves to nothing.
     *
     * Separate from [defaultEmbeddingService], which throws, because [embeddingSetupRequired] calls
     * this during construction. A deployment with no embedding configuration resolves to null here
     * and must still start, because nothing has asked for an embedding yet.
     *
     * A role is resolved on each call through the resolvers, as `default-llm` is. That is done only
     * when configuration declares the name as a role. A name that is not a role is matched against
     * the registered services at startup, and asked of the local runners on each call. When nothing
     * resolves, this returns the placeholder, or null when no placeholder is registered.
     */
    private fun resolveDefaultEmbeddingService(warnOnFallback: Boolean): EmbeddingService? {
        registeredDefaultEmbeddingService?.let { return it }
        if (properties.defaultEmbeddingModelNamesRole()) {
            properties.defaultEmbeddingModel?.let { role ->
                attemptEmbeddingRole(role, ModelSelectionContextHolder.get())?.let { return it }
            }
        } else {
            // The default names a model. It was matched against the registered services once, at
            // startup, so registeredDefaultEmbeddingService is null when the model had not been
            // pulled then. Asking the local runners on each call lets a default that names a local
            // model work as soon as the model is pulled, without a restart. A role is also
            // resolved on each call.
            properties.defaultEmbeddingModel?.let { model ->
                family.locallyServed(model)?.let { return it }
            }
        }
        return placeholderEmbeddingService(warn = warnOnFallback)
    }

    /**
     * The provider behind the deployment's default embedding service. When no user key is active,
     * the embedding [ConfigurableRoleResolver] reads the `embedding-roles` entry for this provider.
     *
     * Reads only registered services, never the resolvers, because the configurable resolver calls
     * this and asking the resolvers would recurse. A deployment whose default is a role and whose
     * key has not arrived has no registered default to read a provider from. Until a key arrives
     * there is no provider to choose entries for.
     *
     * When there is no registered default, this returns a provider only when all registered
     * services other than the placeholder have the same provider. When they have two or more
     * providers it returns null instead of taking the first. [embeddingServices] is in bean
     * registration order, so taking the first would let that order choose which provider's
     * `embedding-roles` entries a call without a user key reads, and adding a module could change
     * the choice. With null, the role is looked up in the flat `embedding-services` map, which is
     * where a deployment with no active user key configures its roles.
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
     * Chat differs: [ChatModelSelection] returns the placeholder for an unsatisfied chat role while
     * the deployment waits for a key. An embedding role does not, because a vector index is created
     * for one model's vector width and the placeholder has no width. If the placeholder were
     * returned here, its [EmbeddingService.awaitingProviderKey] would tell the caller to skip, and
     * a role that names a model nothing serves would be skipped with no error. The same rule is
     * stated on [placeholderEmbeddingService] and in [validate].
     *
     * The default embedding service still falls back to the placeholder. A caller asking for the
     * default asked for whatever the deployment has, and the placeholder tells it, through
     * [EmbeddingService.awaitingProviderKey], that the deployment has no model yet.
     */
    private fun throwNoEmbeddingForRole(criteria: ByRoleModelSelectionCriteria): Nothing {
        logger.warn("No embedding service available for role '{}'", criteria.role)
        throw NoSuitableModelException.forModels(criteria, embeddingServices)
    }

    /**
     * The registered embedding placeholder, if this deployment carries one.
     *
     * Found by [EmbeddingService.awaitingProviderKey] rather than by name, as chat's placeholder is
     * found by its type, because this class must not depend on the BYOK module that implements it.
     *
     * Falling back to the placeholder does not make embedding work. The placeholder cannot embed
     * and does not report a dimension, so every consumer that uses it still fails. It must fail,
     * because a vector index is created for one model's vector width and the placeholder has none.
     * What falling back does is let the deployment start. A consumer that resolves the default
     * embedding service while its beans are created gets the placeholder, instead of an exception
     * that stops the application context before any BYOK code runs. A consumer that creates a
     * vector index should check [EmbeddingService.awaitingProviderKey] and skip until a model that
     * is not the placeholder is registered. It should check that property rather than the type of
     * the service, because a wrapper around the placeholder still reports the property.
     */
    private fun placeholderEmbeddingService(warn: Boolean): EmbeddingService? =
        family.placeholder()
            ?.also { if (warn) reportEmbeddingFallback(it) }

    /**
     * Why the default fell back to the placeholder. Of the three reasons, only [ROLE] is the
     * expected state of a deployment waiting for a key.
     */
    private enum class EmbeddingFallbackReason {
        /** `default-embedding-model` is unset or blank: no embedding model has been chosen yet. */
        UNSET,

        /** It names a role, which nothing has satisfied yet. This is expected before a key arrives. */
        ROLE,

        /** It names a model, and no model is registered under that name: a typo, or a missing module. */
        UNREGISTERED,
    }

    /**
     * The reason and configured name of the last fallback that was logged, so the same fallback is
     * logged once rather than on every call.
     *
     * The default is resolved on every call that asks for the default embedding service, and until
     * a key arrives it falls back every time. Logging each fallback would write one line per
     * embedded chunk instead of one line, and operators would then filter the warning out. The
     * stored value combines the reason and the configured name, so a fallback with a different
     * reason or name, for example after `default-embedding-model` is changed at runtime, is logged
     * again. getAndSet reads and writes in one step, so concurrent first calls log one line, not
     * one each.
     */
    private val lastReportedEmbeddingFallback = AtomicReference<String?>(null)

    /**
     * Logs why the default fell back to the placeholder, with a different message for each
     * [EmbeddingFallbackReason], so the reader knows which setting to change.
     *
     * The operator's key may be present and working when the default is unset or names an
     * unregistered model, so those messages name the property and the registered services instead
     * of reporting a missing key, which would send the operator to look for one.
     *
     * Each reason and configured name is logged once; see [lastReportedEmbeddingFallback].
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
                No embedding model is configured. 'embabel.models.default-embedding-model' is unset or blank, so the '{}' placeholder is used.
                No embedding model has been chosen yet; a provider key is not missing. Set the property to one of the registered services, or to a role. Available: {}
                """.trimIndent(),
                placeholder.name, embeddingServices.map { it.name },
            )

            EmbeddingFallbackReason.ROLE -> logger.info(
                """
                Default embedding service '{}' is a role and is resolved on each call.
                Until a key is supplied, each call gets the '{}' placeholder
                """.trimIndent(),
                configured, placeholder.name,
            )

            EmbeddingFallbackReason.UNREGISTERED -> logger.warn(
                """
                Default embedding service '{}' is not registered, so the '{}' placeholder is used instead.
                Each call fails with a 'No embedding service is configured' error until a model with that name is registered or a local runner serves it. Available: {}
                """.trimIndent(),
                configured, placeholder.name, embeddingServices.map { it.name },
            )
        }
    }

    /**
     * Whether this deployment is waiting for an embedding key: true when the default embedding
     * service resolves at startup to the placeholder. [ChatModelSelection.setupRequired] is the same
     * flag for chat.
     *
     * The two flags are separate because chat keys and embedding keys are configured
     * independently. An application can hold a chat key on the server while embedding keys arrive
     * per user, or the reverse. A deployment with a chat key and no embedding key yet must still
     * start, which is what the placeholder is registered for, so its embedding roles are checked
     * against this flag and not only the chat one.
     */
    private val embeddingSetupRequired: Boolean =
        resolveDefaultEmbeddingService(warnOnFallback = false)?.awaitingProviderKey == true

    /**
     * Throws on a flat `embedding-services` entry whose model is not registered. When the
     * deployment is waiting for a chat key or an embedding key, the entry is expected and only a
     * warning is logged. Nested `embedding-roles` entries that are not registered are only logged
     * at info.
     *
     * @param chatSetupRequired whether the deployment is awaiting a chat key
     */
    fun validate(chatSetupRequired: Boolean) {
        properties.embeddingServices.forEach { (role, model) ->
            if (embeddingServices.none { it.name == model }) {
                /*
                 * The same check as the flat LLM roles in [ChatModelSelection.validate], for the
                 * same reason: a name that is not registered is a typo in a deployment that holds a
                 * key, and expected in one that is waiting for a key. Unlike chat, the role does
                 * not fall back to the placeholder, because a vector index is created for one
                 * model's vector width and the placeholder has none. This check decides only
                 * whether the deployment starts. Asking for the role still throws.
                 *
                 * Either flag lets the deployment start. Chat keys and embedding keys are
                 * configured independently, so a deployment with a chat key whose embedding keys
                 * arrive at runtime is waiting for an embedding key, and [embeddingSetupRequired]
                 * lets it start. [chatSetupRequired] lets the deployment start on its own, because
                 * a deployment waiting for a chat key has registered no models yet, embedding
                 * models included.
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
                    error("Embedding model '$model' for role '$role' is not registered. Available: ${embeddingServices.map { it.name }}")
                }
            }
        }
        /*
         * The nested map is checked differently from the flat map. Chat throws on a nested
         * `roles` entry for the deployment's own provider whose model is not registered, unless
         * the deployment is waiting for a key or that provider's models may arrive after startup.
         * Here an unregistered nested entry is only logged at info, whatever its provider. The
         * nested map names models under providers this deployment may hold no key for, so an
         * unregistered name is expected and is not a typo.
         * Throwing on it would stop a bring-your-own-key deployment from starting.
         */
        properties.embeddingRoles.forEach { (role, byProvider) ->
            byProvider.forEach { (provider, model) ->
                if (embeddingServices.none { it.name == model }) {
                    logger.info(
                        "Embedding model '{}' for role '{}' under provider '{}' is not registered. It can be used once a key supplied at runtime builds it or a local runner serves it",
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
     * The embedding service for [criteria].
     *
     * The `when` lists every [ModelSelectionCriteria] type and has no `else` branch, as in
     * [ChatModelSelection.getLlm]. Adding a criteria type therefore fails compilation of both
     * methods until each handles it. An `else` branch returning the default embedding service
     * would silently answer a named model or a pre-resolved service with the default.
     *
     * The selection context is read once for the whole call and passed to each branch that resolves
     * a role or a name, so those branches resolve with the same context. The default branch reads
     * the context again, in [resolveDefaultEmbeddingService].
     */
    fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService {
        val context = ModelSelectionContextHolder.get()
        return when (criteria) {
            // Resolved on each call through the resolvers, so a key stored after startup
            // satisfies this role on the next request, without a restart.
            is ByRoleModelSelectionCriteria ->
                attemptEmbeddingRole(criteria.role, context) ?: throwNoEmbeddingForRole(criteria)

            /*
             * A named model is never replaced by the default. If it were, an application offering
             * "change the embedding model" would report success, re-embed its whole corpus, and
             * keep the same model, with nothing to say the request was ignored.
             */
            is ByNameModelSelectionCriteria ->
                attemptEmbeddingName(criteria.name, context) ?: throwNoEmbeddingForName(criteria)

            // Picks uniformly at random among the names this deployment can serve, as getLlm
            // does. The names are shuffled and the first that resolves is used.
            is RandomByNameModelSelectionCriteria ->
                criteria.names.shuffled()
                    .firstNotNullOfOrNull { attemptEmbeddingName(it, context) }
                    ?: throwNoEmbeddingForName(criteria)

            // The first name that resolves. Each name that does not resolve is logged at info, as
            // in getLlm, because a name that does not resolve here is expected and not an error.
            is FallbackByNameModelSelectionCriteria ->
                criteria.names
                    .firstNotNullOfOrNull { name ->
                        attemptEmbeddingName(name, context).also {
                            if (it == null) logger.info("Requested embedding model '{}' not available", name)
                        }
                    }
                    ?: throwNoEmbeddingForName(criteria)

            /*
             * The caller already holds the service, so it is returned as it is and must not be
             * replaced. Under BYOK the service was built on the user's key. Returning the
             * deployment default instead would embed on the deployment's key, bill the
             * deployment, and report success.
             *
             * This uses a checked cast (`as?`), unlike the unchecked cast in
             * [ChatModelSelection.getLlm]. Criteria can be deserialized by Jackson and are
             * generic, so a payload holding a service of the wrong type can arrive.
             * NoSuitableModelException names the criteria, where a ClassCastException would name
             * only the two erased types.
             */
            is PreResolvedModelSelectionCriteria<*> ->
                criteria.resolved as? EmbeddingService ?: throwNoEmbeddingForName(criteria)

            /*
             * Both criteria mean the default embedding service.
             *
             * In [ChatModelSelection.getLlm], AUTO is an error, because the layer above resolves
             * it before that point. Nothing chooses an embedding model by looking at a prompt, so
             * for embeddings AUTO means whatever model this deployment has. The caller can check
             * EmbeddingService.awaitingProviderKey on the result to see whether it is the
             * placeholder.
             */
            is AutoModelSelectionCriteria, is DefaultModelSelectionCriteria -> defaultEmbeddingService()
        }
    }

    /**
     * The embedding service a caller named, or null when this deployment cannot serve that name.
     *
     * Registered services are checked first. For a deployment whose models are built from its own
     * key at startup, that check is enough. A deployment whose key arrives later has no such
     * service registered, so the named model is built from that key, as a role's model is. The
     * model name is known, and the key is found by resolving the default embedding role (see
     * [embeddingFromDeploymentCredential]).
     *
     * A configured role name is also accepted here, because `default-embedding-model` can name a
     * role. An application that reads the deployment's default and asks for it by name is asking
     * for a role, and without this the value from the configuration would be rejected. Registered
     * models are checked before roles, and a name is resolved as a role only when
     * [ConfigurableModelProviderProperties.namesEmbeddingRole] says it is one, so a name the
     * caller meant as a model is not sent to the resolvers.
     *
     * A registered placeholder is never returned for its own name. A role may be bound to the
     * placeholder on purpose (see [ModelFamily.materialize]), and the placeholder then means the
     * role has no model yet. A caller that names a model needs that model's vector width, and the
     * placeholder has none.
     */
    private fun attemptEmbeddingName(name: String, context: ModelSelectionContext): EmbeddingService? =
        embeddingServices.firstOrNull { it.name == name && !it.awaitingProviderKey }
            ?: name.takeIf { properties.namesEmbeddingRole(it) }?.let { attemptEmbeddingRole(it, context) }
            // Asked before the deployment credential. A model a local runner already serves costs
            // nothing and needs no key, so building it on a provider's key would cost more for the
            // same model.
            ?: family.locallyServed(name)
            ?: embeddingFromDeploymentCredential(name, context)

    /**
     * Logs a warning and throws when nothing can serve a named embedding model.
     *
     * No other model is returned instead. A vector index is created for one model's vector width,
     * so returning a different model would put two models' vectors in one corpus with nothing
     * reporting it. Re-embedding a corpus is the most expensive operation a caller has, and the
     * only one that cannot be undone. [throwNoEmbeddingForRole] throws for roles for the same
     * reason.
     */
    private fun throwNoEmbeddingForName(criteria: ModelSelectionCriteria): Nothing {
        logger.warn(
            """
            No embedding service for {}. Registered: {}. Register the model, or set embabel.models.default-embedding-model
            to a role that has an embabel.models.embedding-roles entry for the provider of the user's key, so the model can be built on that key
            """.trimIndent(),
            criteria, embeddingServices.map { it.name },
        )
        throw NoSuitableModelException.forModels(criteria, embeddingServices)
    }

    /**
     * Build [model] from whatever provider key this deployment would use for its default embedding
     * role, or null when it has no such key.
     *
     * No credential is passed in, so it is found by resolving the default embedding role. The
     * resolvers answer per role, and the default role is where the deployment configures which key
     * its embeddings use. When `default-embedding-model` names a model and not a role, there is no
     * role to resolve, and this returns null.
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
        // Only a Credential answer carries a key to build the named model on. A Bound or Service
        // answer names a model of its own, which is not the model the caller asked for.
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
                        """
                        No model platform built an embedding service for provider '{}', which the named model '{}' needs.
                        Register a CredentialEndpointResolver or a ModelPlatform that builds embedding models for provider '{}',
                        and check that the module that calls that provider's API is on the classpath
                        """.trimIndent(),
                        credential.provider, model, credential.provider,
                    )
                }
            }
    }

    /** Every embedding model name a caller could ask for. */
    fun listableNames(): List<String> = family.listableNames()

    companion object {

        /** The properties an embedding role's model is looked up in, named in log messages. */
        const val ROLES_PROPERTIES = "embabel.models.embedding-roles or embabel.models.embedding-services"
    }
}
