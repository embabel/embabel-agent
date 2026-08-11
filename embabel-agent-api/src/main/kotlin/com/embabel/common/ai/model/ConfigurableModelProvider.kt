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
import com.embabel.agent.spi.PlaceholderEmbeddingService
import com.embabel.agent.spi.PlaceholderLlmService
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
        return llms.values.toSet() + defaultLlm
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
) : ModelProvider {

    private val logger = loggerFor<ConfigurableModelProvider>()

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
     * Consumers that provision a vector index should ask [EmbeddingService.awaitingKey] and skip
     * until a real model is registered - the property, not a type test, because it survives
     * wrapping.
     */
    private fun placeholderEmbeddingService(warn: Boolean): EmbeddingService? =
        embeddingServices.firstOrNull { it.awaitingKey }
            ?.also {
                if (warn) logger.warn(
                    "Default embedding service '{}' is not registered; falling back to the '{}' placeholder. " +
                        "Embedding will fail with an actionable 'no embedding service configured' error until a key is supplied. Available: {}",
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
        resolveDefaultEmbeddingService(warnOnFallback = false)?.awaitingKey == true

    init {
        properties.llms.forEach { (role, model) ->
            if (llms.none { it.name == model }) {
                /*
                 * Fatal, unless this deployment is waiting for a key. A name that resolves to
                 * nothing is a typo in a deployment that has one, and letting it start would move
                 * the failure to whichever unrelated call first asks for that role. A deployment in
                 * setup-required mode has no models registered yet by definition, so the same name
                 * is expected there and only worth reporting.
                 */
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
                        "Embedding model '{}' for role '{}' is not registered. This deployment is awaiting a key, " +
                            "so that is expected; asking for that role will still fail. Available: {}",
                        model, role, embeddingServices.map { it.name },
                    )
                } else {
                    error("Embedding model '$model' for role $role is not available: Choices are ${embeddingServices.map { it.name }}")
                }
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

    override fun listRoles(modelClass: Class<*>): List<String> {
        return when {
            LlmService::class.java.isAssignableFrom(modelClass) -> properties.llms.keys.toList()
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
                val modelName = properties.llms[criteria.role] ?: throw NoSuitableModelException(criteria, llms.map { it.name })
                llms.firstOrNull { it.name == modelName } ?: throw NoSuitableModelException(criteria, llms.map { it.name })
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
}
