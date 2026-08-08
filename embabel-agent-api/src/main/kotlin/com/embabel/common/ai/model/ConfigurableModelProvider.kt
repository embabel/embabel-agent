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
     * Takes precedence over [llms] for the active provider. Unlike [llms], an entry naming a model
     * that is not registered is not an error: it applies only when that provider is the active one.
     */
    var roles: Map<String, Map<String, LlmOptions>> = emptyMap(),
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
) {

    fun allWellKnownLlmNames(): Set<String> {
        return llms.values.toSet() + roles.values.flatMap { it.values }.mapNotNull { it.modelName } + defaultLlm
    }

    fun allWellKnownEmbeddingServiceNames(): Set<String> {
        return embeddingServices.values.toSet() + setOfNotNull(defaultEmbeddingModel)
    }
}

/**
 * Take LLM definitions from configuration
 */
class ConfigurableModelProvider(
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
                size > MAX_CACHED_CREDENTIAL_SERVICES
        }.let { java.util.Collections.synchronizedMap(it) }

    private val defaultLlm =
        if (llms.isNotEmpty())
            llms.firstOrNull { it.name == properties.defaultLlm }
                ?: throw IllegalArgumentException(
                    "Default LLM '${properties.defaultLlm}' not found. Set the 'embabel.models.default-llm' property to one of the available models: ${llms.map { it.name }}.")
        else
            throw IllegalArgumentException("No models detected. Ensure that at least one Embabel Agent Starter (e.g. embabel-agent-starter-openai) is on the classpath and models are loaded into it.")

    // Compute this lazily as embedding services may not be available
    private fun defaultEmbeddingService() =
        embeddingServices.firstOrNull { it.name == properties.defaultEmbeddingModel }
            ?: throw IllegalArgumentException("Default embedding service '${properties.defaultEmbeddingModel}' not found in available models: ${embeddingServices.map { it.name }}")

    init {
        properties.llms.forEach { (role, model) ->
            if (llms.none { it.name == model }) {
                // Not fatal: the deployment may be keyed for a different provider than the one this
                // role names, and a role that cannot be satisfied falls back to the default LLM.
                logger.warn(
                    "LLM '{}' for role '{}' is not available, so the role will fall back to the default LLM '{}'. Available: {}",
                    model, role, properties.defaultLlm, llms.map { it.name },
                )
            }
        }
        logger.info(infoString(verbose = true))

        properties.embeddingServices.forEach { (role, model) ->
            if (embeddingServices.none { it.name == model }) {
                error("Embedding model '$model' for role $role is not available: Choices are ${embeddingServices.map { it.name }}")
            }
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
            .copy(modelSelectionCriteria = ModelSelectionCriteria.preResolved(resolved.llmService))
    }

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
        logger.warn(
            "No model available for role '{}' (provider: {})",
            role, context.provider ?: "deployment default",
        )
        throw NoSuitableModelException(ByRoleModelSelectionCriteria(role), llms.map { it.name })
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
        val key = CredentialModelKey(credential, model)
        val llmService = credentialLlmServices[key]
            ?: credentialLlmServiceFactories
                .firstNotNullOfOrNull { it.createLlmService(credential, model) }
                ?.also { credentialLlmServices[key] = it }
        if (llmService == null) {
            logger.warn(
                "No CredentialLlmServiceFactory handles provider '{}', needed for role '{}'",
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
     * Cache key for a service built from a user's key. Holds the credential itself rather than a
     * hash of it, so two distinct keys can never collide onto one another's service.
     */
    private data class CredentialModelKey(
        val credential: ProviderCredential,
        val model: String,
    )

    companion object {

        /**
         * Upper bound on services built from user keys. Generous: each is a thin wrapper around a
         * chat client, and a deployment with more concurrent keys than this will simply rebuild.
         */
        const val MAX_CACHED_CREDENTIAL_SERVICES = 500
    }
}
