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
     * Default LLM name. Must be an LLM name. It's good practice to override this in configuration.
     */
    var defaultLlm: String = "gpt-4.1-mini",
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
        return llms.values.toSet() + roles.values.flatMap { it.values }.mapNotNull { it.modelName } + defaultLlm
    }

    /**
     * The embedding counterpart of [allWellKnownLlmNames]. Shorter because embedding roles have no
     * nested per-provider shape: a role maps to one name, and there is `default-embedding-model`.
     */
    fun allWellKnownEmbeddingServiceNames(): Set<String> {
        return embeddingServices.values.toSet() + setOfNotNull(defaultEmbeddingModel)
    }
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

    private val defaultLlm =
        if (llms.isNotEmpty())
            llms.firstOrNull { it.name == properties.defaultLlm }
                ?: placeholderLlm()
                ?: throw IllegalArgumentException(
                    "Default LLM '${properties.defaultLlm}' not found. Set the 'embabel.models.default-llm' property to one of the available models: ${llms.map { it.name }}.")
        else
            throw IllegalArgumentException("No models detected. Ensure that at least one Embabel Agent Starter (e.g. embabel-agent-starter-openai) is on the classpath and models are loaded into it.")

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
                logger.warn(
                    "Default LLM '{}' is not registered; falling back to the '{}' placeholder. " +
                        "Calls will fail with an actionable 'no LLM configured' error until a key is supplied. Available: {}",
                    properties.defaultLlm, it.name, llms.map { it.name },
                )
            }

    // Compute this lazily as embedding services may not be available
    private fun defaultEmbeddingService() =
        resolveDefaultEmbeddingService(warnOnFallback = true)
            ?: throw IllegalArgumentException("Default embedding service '${properties.defaultEmbeddingModel}' not found in available models: ${embeddingServices.map { it.name }}")

    /**
     * What `default-embedding-model` resolves to, or null if it resolves to nothing.
     *
     * Separate from [defaultEmbeddingService] because [embeddingSetupRequired] has to ask the
     * question during construction, where throwing would take down the deployments this whole
     * mechanism exists to let start - one with no embedding configuration at all resolves to
     * nothing here and must still boot, since nothing has asked for an embedding yet.
     */
    private fun resolveDefaultEmbeddingService(warnOnFallback: Boolean): EmbeddingService? =
        embeddingServices.firstOrNull { it.name == properties.defaultEmbeddingModel }
            ?: placeholderEmbeddingService(warn = warnOnFallback)

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
    private fun placeholderEmbeddingService(warn: Boolean): EmbeddingService? =
        embeddingServices.firstOrNull { it.awaitingProviderKey }
            ?.also {
                if (warn) logger.warn(
                    """
                    Default embedding service '{}' is not registered; falling back to the '{}' placeholder.
                    Embedding will fail with an actionable 'no embedding service configured' error until a key is supplied. Available: {}
                    """.trimIndent(),
                    properties.defaultEmbeddingModel, it.name, embeddingServices.map { it.name },
                )
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
            "embabel.models.credential-service-cache-size must be at least 1, was ${properties.credentialServiceCacheSize}. " +
                "Zero or negative evicts every entry on insert, so nothing is ever cached and every call rebuilds a service."
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
                        "LLM '{}' for role '{}' is not registered. This deployment is awaiting a key, so that is expected; " +
                            "the role will report 'no LLM configured' until one is supplied. Available: {}",
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
        val resolution = roleResolvers.firstNotNullOfOrNull { it.resolve(role, context) }
        val resolved = when (resolution) {
            is RoleResolution.Service -> ResolvedRole(resolution.llmService, LlmOptions.withDefaults())

            is RoleResolution.Options -> byName(resolution.llmOptions)
                ?.let { ResolvedRole(it, resolution.llmOptions) }

            is RoleResolution.Credential -> fromCredential(role, resolution.credential)

            null -> null
        }
        if (resolved != null) {
            return resolved
        }
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

    override fun listRoles(modelClass: Class<*>): List<String> {
        return when {
            LlmService::class.java.isAssignableFrom(modelClass) ->
                (properties.llms.keys + properties.roles.keys).toList()
            EmbeddingService::class.java.isAssignableFrom(modelClass) -> properties.embeddingServices.keys.toList()
            else -> throw IllegalArgumentException("Unsupported model class: $modelClass")
        }
    }

    override fun listModelNames(modelClass: Class<*>): List<String> {
        return when {
            LlmService::class.java.isAssignableFrom(modelClass) -> llms.map { it.name }
            EmbeddingService::class.java.isAssignableFrom(modelClass) -> embeddingServices.map { it.name }
            else -> throw IllegalArgumentException("Unsupported model class: $modelClass")
        }
    }

    override fun getLlm(criteria: ModelSelectionCriteria): LlmService<*> =
        when (criteria) {
            is ByRoleModelSelectionCriteria -> {
                resolveRole(criteria.role, ModelSelectionContextHolder.get()).llmService
            }

            is ByNameModelSelectionCriteria -> {
                llms.firstOrNull { it.name == criteria.name } ?: throw NoSuitableModelException(criteria, llms.map { it.name })
            }

            is RandomByNameModelSelectionCriteria -> {
                val models = llms.filter { criteria.names.contains(it.name) }
                if (models.isEmpty()) {
                    throw NoSuitableModelException(criteria, llms.map { it.name })
                }
                models.random()
            }

            is FallbackByNameModelSelectionCriteria -> {
                var llm: LlmService<*>? = null
                for (requestedName in criteria.names) {
                    llm = llms.firstOrNull { requestedName == it.name }
                    if (llm != null) {
                        break
                    } else {
                        logger.info("Requested LLM '{}' not found", requestedName)
                    }
                }
                llm
                    ?: throw NoSuitableModelException(criteria, llms.map { it.name })
            }

            is AutoModelSelectionCriteria -> {
                // The infrastructure above this class should have resolved this
                error("Auto model selection criteria should have been resolved upstream")
            }

            is DefaultModelSelectionCriteria -> {
                defaultLlm
            }

            is PreResolvedModelSelectionCriteria<*> -> {
                @Suppress("UNCHECKED_CAST")
                criteria.resolved as LlmService<*>
            }
        }

    override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService =
        when (criteria) {
            is ByRoleModelSelectionCriteria -> {
                val modelName =
                    properties.embeddingServices[criteria.role] ?: throw NoSuitableModelException.forModels(
                        criteria,
                        embeddingServices,
                    )
                embeddingServices.firstOrNull { it.name == modelName } ?: throw NoSuitableModelException.forModels(
                    criteria,
                    embeddingServices,
                )
            }

            // TODO should handle other criteria
            else -> {
                defaultEmbeddingService()
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
