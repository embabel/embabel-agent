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
package com.embabel.agent.config.models.ollama

import com.embabel.agent.api.models.OllamaModels
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.*
import com.embabel.common.ai.model.local.DiscoveryFailureReporter
import com.embabel.common.ai.model.local.LocalModel
import com.embabel.common.ai.model.local.LocalModelBeans
import com.embabel.common.ai.model.local.LocalModelCatalog
import com.embabel.common.ai.model.local.LocalModelDiscoveryProperties
import com.embabel.common.ai.model.local.LocalModelEmbeddingRoleResolver
import com.embabel.common.ai.model.local.LocalModelKind
import com.embabel.common.ai.model.local.LocalModelRoleResolver
import com.embabel.common.ai.model.local.LocalModelSource
import com.embabel.common.util.ObjectProviders
import com.fasterxml.jackson.annotation.JsonProperty
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions
import org.springframework.ai.ollama.api.ThinkOption
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * Load Ollama local models, both LLMs and embedding models.
 * This class will always be loaded, but models won't be loaded
 * from Ollama unless the "ollama" profile is set.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaNodeProperties::class, LocalModelDiscoveryProperties::class)
class OllamaModelsConfig(
    @param:Value("\${embabel.agent.platform.models.ollama.base-url:\${spring.ai.ollama.base-url:}}")  // fallback to spring ai
    private val baseUrl: String,
    private val nodeProperties: OllamaNodeProperties?,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val properties: ConfigurableModelProviderProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val localModelDiscoveryProperties: LocalModelDiscoveryProperties,
    @Qualifier("aiModelRestClientBuilder")
    private val restClientBuilder: ObjectProvider<RestClient.Builder> = ObjectProviders.empty(),
) {
    private val logger = LoggerFactory.getLogger(OllamaModelsConfig::class.java)

    private val discoveryFailures = DiscoveryFailureReporter(logger)

    private companion object {
        /** Connect and read budget for a model listing against an Ollama server. */
        private val DISCOVERY_TIMEOUT = Duration.ofSeconds(2)
    }

    private data class ModelResponse(
        @param:JsonProperty("models") val models: List<ModelDetails>,
    )

    private data class ModelDetails(
        @param:JsonProperty("name") val name: String,
        @param:JsonProperty("size") val size: Long,
        @param:JsonProperty("modified_at") val modifiedAt: String,
    )

    private data class Model(
        val name: String,
        val model: String,
        val size: Long,
    )

    //AH, consider refactoring (create issue) to avoid mutable state
    // but for now it's simpler this way
    private var providerInitialization: ProviderInitialization = ProviderInitialization(
        provider = OllamaModels.PROVIDER,
        registeredLlms = emptyList(),
        registeredEmbeddings = emptyList()
    )

    private fun restClientBuilder(): RestClient.Builder =
        restClientBuilder.getIfAvailable { RestClient.builder() }
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })

    /**
     * Bounded, and a clone rather than the shared builder: discovery is no longer only a startup
     * call - [OllamaModelSource] makes it on the path of an embedding - and a server that accepts a
     * connection and never answers would hang that call rather than decline it. The chat and
     * embedding clients keep the injected builder's own settings.
     */
    private val discoveryClient: RestClient by lazy {
        restClientBuilder().clone()
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(DISCOVERY_TIMEOUT)
                setReadTimeout(DISCOVERY_TIMEOUT)
            })
            .build()
    }

    private fun loadModelsFromUrl(baseUrl: String): List<Model> =
        try {
            val response = discoveryClient.get()
                .uri("$baseUrl/api/tags")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<ModelResponse>()

            discoveryFailures.succeeded(baseUrl)
            response?.models?.mapNotNull { modelDetails ->
                // Additional validation to ensure model names are valid
                if (modelDetails.name.isNotBlank()) {
                    Model(
                        name = modelDetails.name.replace(":", "-").lowercase(),
                        model = modelDetails.name,
                        size = modelDetails.size
                    )
                } else null
            } ?: emptyList()
        } catch (e: Exception) {
            discoveryFailures.failed(baseUrl, e)
            emptyList()
        }

    private fun loadModels(): List<Model> {
        return loadModelsFromUrl(this.baseUrl)
    }


    @Bean
    fun ollamaModelsInitializer(): ProviderInitialization {
        val nodes = nodeProperties?.nodes?.takeIf { it.isNotEmpty() }
        val hasDefaultUrl = baseUrl.isNotBlank()

        when {
            hasDefaultUrl && nodes == null -> {
                logger.info("Using default Ollama instance at {}", baseUrl)
                registerDefaultMode()
            }

            !hasDefaultUrl && nodes != null -> {
                logger.info("Using {} Ollama nodes", nodes.size)
                registerMultiNodeOnlyMode()
            }

            hasDefaultUrl && nodes != null -> {
                logger.info("Using default instance + {} nodes", nodes.size)
                registerHybridMode()
            }

            else -> {
                logger.warn("No Ollama configuration found. Skipping model registration.")
            }
        }
        return this.providerInitialization
    }

    /**
     * A chat service LISTED under the node-prefixed name and ASKING the server for [modelName].
     *
     * The two differ only for a node: `gpu-qwen3:latest` is how the platform tells two nodes' copies
     * apart, and `qwen3:latest` is the only name the node answers to. The request name is pinned in
     * [OllamaOptionsConverter] as well as on the model, because [SpringAiLlmService] stamps its own
     * name onto every request's options.
     */
    private fun ollamaLlmOf(modelName: String, baseUrl: String, nodeName: String? = null): LlmService<*> {
        val uniqueModelName = createUniqueModelName(modelName, nodeName)
        val springChatModel = OllamaChatModel.builder()
            .ollamaApi(
                OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .restClientBuilder(restClientBuilder())
                    .webClientBuilder(
                        WebClient.builder()
                            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    )
                    .build()
            )
            .options(
                OllamaChatOptions.builder()
                    .model(modelName)
                    .build()
            )
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .build()

        return SpringAiLlmService(
            name = uniqueModelName,
            chatModel = springChatModel,
            provider = OllamaModels.PROVIDER,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            optionsConverter = OllamaOptionsConverter(servedModelName = modelName),
            thinkingSupported = true,
        )
    }

    private fun ollamaLlmOf(name: String): LlmService<*> {
        return ollamaLlmOf(name, this.baseUrl)
    }


    private fun ollamaEmbeddingServiceOf(
        modelName: String,
        baseUrl: String,
        nodeName: String? = null
    ): EmbeddingService {
        val uniqueModelName = createUniqueModelName(modelName, nodeName)
        val springEmbeddingModel = OllamaEmbeddingModel.builder()
            .ollamaApi(
                OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .restClientBuilder(restClientBuilder())
                    .webClientBuilder(
                        WebClient.builder()
                            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    )
                    .build()
            )
            .options(
                OllamaEmbeddingOptions.builder()
                    .model(modelName)
                    .build()
            )
            .build()

        return SpringAiEmbeddingService(
            name = uniqueModelName,
            model = springEmbeddingModel,
            provider = OllamaModels.PROVIDER,
        )
    }

    private fun ollamaEmbeddingServiceOf(name: String): EmbeddingService {
        return ollamaEmbeddingServiceOf(name, this.baseUrl)
    }

    private fun normalizeModelNameForBean(model: Model): String {
        return model.model.replace(":", "-").lowercase()
    }

    private fun createUniqueModelName(modelName: String, nodeName: String?): String {
        return nodeName?.let { "$it-$modelName" } ?: modelName
    }

    private fun registerModelsFromUrl(
        baseUrl: String,
        nodeName: String? = null,
        beanNameProvider: (Model) -> List<String>
    ) {
        val models = loadModelsFromUrl(baseUrl)
        val contextName = if (nodeName == null) "default instance" else "node '$nodeName'"

        if (models.isEmpty()) {
            logger.warn("No Ollama models discovered from {} at {}. Check server configuration.", contextName, baseUrl)
        } else {
            logger.info("Discovered {} Ollama models from {}: {}", models.size, contextName, models.map { it.name })
        }

        val registeredLlms = mutableListOf<RegisteredModel>()
        val registeredEmbeddings = mutableListOf<RegisteredModel>()

        models.forEach { model ->
            try {
                if (properties.allWellKnownEmbeddingServiceNames().contains(model.model)) {
                    val embeddingService = ollamaEmbeddingServiceOf(model.model, baseUrl, nodeName)

                    // Use node-aware naming for embeddings too
                    beanNameProvider(model).forEach { beanName ->
                        val embeddingBeanName = beanName.replace("ollamaModel-", "ollamaEmbeddingModel-")
                        configurableBeanFactory.registerSingleton(embeddingBeanName, embeddingService)
                        registeredEmbeddings.add(RegisteredModel(beanName = beanName, modelId = model.name))
                        logger.debug(
                            "Successfully registered Ollama embedding service {} as bean {}",
                            model.name,
                            embeddingBeanName,
                        )
                    }
                } else {
                    val llm = ollamaLlmOf(model.model, baseUrl, nodeName)

                    // Register with all provided bean names
                    beanNameProvider(model).forEach { beanName ->
                        configurableBeanFactory.registerSingleton(beanName, llm)
                        registeredLlms.add(RegisteredModel(beanName = beanName, modelId = model.name))
                        logger.debug(
                            "Successfully registered Ollama LLM {} as bean {}",
                            model.name,
                            beanName,
                        )
                    }
                }

            } catch (e: Exception) {
                logger.error("Failed to register Ollama model {}: {}", model.name, e.message)
            }
        }

        this.providerInitialization = ProviderInitialization(
            provider = OllamaModels.PROVIDER,
            registeredLlms = registeredLlms,
            registeredEmbeddings = registeredEmbeddings
        ).also { logger.info(it.summary()) }
    }

    private fun registerDefaultMode() {
        registerModelsFromUrl(baseUrl, nodeName = null) { model ->
            val normalizedName = normalizeModelNameForBean(model)
            listOf(
                "ollamaModel-${normalizedName}"           // backward compatibility only
            )
        }
    }

    private fun registerMultiNodeOnlyMode() {
        nodeProperties?.nodes?.forEach { node ->
            registerNodeModels(node.name, node.baseUrl)
        }
    }

    private fun registerHybridMode() {
        registerDefaultMode()
        registerMultiNodeOnlyMode()
    }

    private fun registerNodeModels(nodeName: String, nodeBaseUrl: String) {
        registerModelsFromUrl(nodeBaseUrl, nodeName = nodeName) { model ->
            val normalizedName = normalizeModelNameForBean(model)
            listOf("ollamaModel-${nodeName}-${normalizedName}")
        }
    }

    /**
     * What a Ollama model is for.
     *
     * Ollama's listing does not say, so configuration does - through the shared rule, which is also
     * what startup registration here applies. One function, so a model cannot land in one category
     * at boot and the other when pulled later.
     */
    private fun kindOf(modelName: String): LocalModelKind =
        LocalModelKind.fromConfiguration(modelName, properties)

    /**
     * An Ollama server this deployment is configured to use: the default instance, or a named node.
     */
    private data class OllamaEndpoint(
        val nodeName: String?,
        val baseUrl: String,
    )

    /** A model an endpoint is serving, under the raw name Ollama itself knows it by. */
    private data class ServedOllamaModel(
        val modelName: String,
        val endpoint: OllamaEndpoint,
    )

    /**
     * Every endpoint that may serve a model, in the same order registration considers them.
     */
    private fun ollamaEndpoints(): List<OllamaEndpoint> = buildList {
        if (baseUrl.isNotBlank()) {
            add(OllamaEndpoint(nodeName = null, baseUrl = baseUrl))
        }
        nodeProperties?.nodes?.forEach { add(OllamaEndpoint(nodeName = it.name, baseUrl = it.baseUrl)) }
    }

    /**
     * Which endpoint is serving [uniqueModelName], and what Ollama calls the model there.
     *
     * Asks the endpoints again rather than remembering the listing, which costs one request per
     * endpoint the first time a model is built and keeps this free of state that could go stale.
     * The raw name is carried back because a node-scoped model is asked for under a prefixed name
     * and served under its own.
     */
    private fun servedOllamaModel(uniqueModelName: String): ServedOllamaModel? =
        ollamaEndpoints().firstNotNullOfOrNull { endpoint ->
            loadModelsFromUrl(endpoint.baseUrl)
                .firstOrNull { createUniqueModelName(it.model, endpoint.nodeName) == uniqueModelName }
                ?.let { ServedOllamaModel(modelName = it.model, endpoint = endpoint) }
        }

    /**
     * The Ollama server(s), asked per call rather than once at startup.
     *
     * [ollamaModelsInitializer] still registers what was pulled at boot; this covers `ollama pull`
     * afterwards, which otherwise needed a restart before anything could use the model.
     */
    private inner class OllamaModelSource : LocalModelSource {

        override val provider: String = OllamaModels.PROVIDER

        override fun servedModels(): Set<LocalModel> =
            ollamaEndpoints()
                .flatMap { endpoint ->
                    loadModelsFromUrl(endpoint.baseUrl).map {
                        LocalModel(
                            name = createUniqueModelName(it.model, endpoint.nodeName),
                            kind = kindOf(it.model),
                        )
                    }
                }
                .toSet()

        override fun llmService(model: String): LlmService<*>? =
            servedOllamaModel(model)?.let {
                ollamaLlmOf(it.modelName, it.endpoint.baseUrl, it.endpoint.nodeName)
            }

        override fun embeddingService(model: String): EmbeddingService? =
            servedOllamaModel(model)?.let {
                ollamaEmbeddingServiceOf(it.modelName, it.endpoint.baseUrl, it.endpoint.nodeName)
            }
    }

    /**
     * The catalog and the two resolvers, built once over one [LocalModelSource].
     *
     * Lazy, so a configuration that is loaded but never asked for a model never constructs one, and
     * a field rather than a bean method because with `proxyBeanMethods = false` a bean method called
     * three times would build three catalogs and the resolvers would stop sharing a cache.
     */
    private val localModelBeans: LocalModelBeans by lazy {
        LocalModelBeans(OllamaModelSource(), properties, localModelDiscoveryProperties)
    }

    @Bean
    fun ollamaLocalModelCatalog(): LocalModelCatalog = localModelBeans.catalog

    @Bean
    fun ollamaLocalModelRoleResolver(): LocalModelRoleResolver = localModelBeans.roleResolver

    @Bean
    fun ollamaLocalModelEmbeddingRoleResolver(): LocalModelEmbeddingRoleResolver =
        localModelBeans.embeddingRoleResolver
}

/**
 * @param servedModelName the name the Ollama server knows the model by, when it differs from the
 * service's own name - a node-scoped service is named `<node>-<model>`, and sending that to the node
 * asks for a model it does not have. Null sends the service name, which is right for the default
 * instance.
 */
class OllamaOptionsConverter(
    private val thinkLevelsSupported: Boolean = false,
    private val servedModelName: String? = null,
) : OptionsConverter {

    private companion object {
        const val OLLAMA_THINK_LEVEL_LOW_THRESHOLD = 2000
        const val OLLAMA_THINK_LEVEL_MEDIUM_THRESHOLD = 4000
    }

    override fun convertOptions(options: LlmOptions, model: String): ChatOptions {
        val builder = OllamaChatOptions.builder()
            .model(servedModelName ?: model)
            .temperature(options.temperature)
            .topP(options.topP)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .topK(options.topK)
        toThinkOption(options.thinking)?.let { builder.thinkOption(it) }
        return builder.build()
    }

    /**
     * Maps embabel's [Thinking] config to the Ollama API's `think` parameter.
     *
     * **Three distinct states — all matter:**
     *  - `think=true`  — native thinking: model reasons internally; Ollama routes the reasoning to
     *                    a separate `message.thinking` field, NOT to `message.content`. Triggered
     *                    when [Thinking.tokenBudget] is non-null.
     *  - `think=false` — thinking suppressed: Ollama injects an empty `<think></think>` block that
     *                    signals the model to skip all reasoning. Prompt instructions asking for
     *                    `<think>` tags are overridden and ignored by the model.
     *  - *(absent)*    — model default: Ollama omits the parameter entirely. Models like
     *                    deepseek-r1 then output `<think>...</think>` naturally in `message.content`,
     *                    which [extractAllThinkingBlocks] can extract. This is the correct mode for
     *                    prompt-driven thinking ([Thinking.withExtraction]).
     *
     * This method returns `null` when no budget is set, intentionally omitting the `think`
     * parameter from the request so prompt-driven models behave correctly.
     *
     * **Two-gate model for end-to-end thinking:**
     *  1. embabel gate — [Thinking.enabled]=true, enforced by [PromptRunner.thinking] which rejects
     *                    [Thinking.NONE]. Also consumed by Anthropic/Google GenAI converters.
     *  2. Ollama gate  — THIS method. Both gates must be open for thinking to occur.
     *
     * [Thinking.extractThinking] is orthogonal to both gates — it controls embabel's post-processing
     * that strips `<think>` tags and populates [ThinkingResponse.thinkingBlocks].
     */
    private fun toThinkOption(thinkingConfig: Thinking?): ThinkOption? {
        // No thinking config at all → suppress qwen3/deepseek default thinking behavior
        if (thinkingConfig == null) return ThinkOption.ThinkBoolean.DISABLED
        // withExtraction() has no budget → omit think so model outputs <think> naturally in content
        val budget = thinkingConfig.tokenBudget ?: return null

        // level-capable models (e.g. gpt-oss) map budget to Ollama's three effort strings.
        // Ollama defines exactly three levels (LOW/MEDIUM/HIGH); HIGH is the ceiling.
        //   budget < 2000  → LOW    (light reasoning)
        //   budget < 4000  → MEDIUM (moderate reasoning)
        //   budget >= 4000 → HIGH   (maximum reasoning; no higher level exists)
        if (thinkLevelsSupported) return when {
            budget < OLLAMA_THINK_LEVEL_LOW_THRESHOLD -> ThinkOption.ThinkLevel.LOW
            budget < OLLAMA_THINK_LEVEL_MEDIUM_THRESHOLD -> ThinkOption.ThinkLevel.MEDIUM
            else -> ThinkOption.ThinkLevel.HIGH
        }

        // boolean-only models (e.g. qwen3): budget presence drives think=true (native thinking).
        // extractThinking is an embabel-side concern, not an Ollama API concern.
        return ThinkOption.ThinkBoolean.ENABLED
    }
}
