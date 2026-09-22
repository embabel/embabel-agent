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
package com.embabel.agent.config.models.docker

import com.embabel.agent.api.models.DockerLocalModels.Companion.PROVIDER
import com.embabel.agent.config.models.docker.DockerRetryProperties.Companion.PREFIX
import com.embabel.agent.openai.OpenAiChatOptionsConverter
import com.embabel.agent.spi.common.RetryProperties
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
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Duration


@ConfigurationProperties(prefix = PREFIX)
class DockerRetryProperties : RetryProperties {

    /**
     *  Maximum number of attempts.
     */
    override var maxAttempts: Int = 10

    /**
     * Initial backoff interval (in milliseconds).
     */
    override var backoffMillis: Long = 5000L

    /**
     * Backoff interval multiplier.
     */
    override var backoffMultiplier: Double = 5.0

    /**
     * Maximum backoff interval (in milliseconds).
     */
    override var backoffMaxInterval: Long = 180000L

    override val propertyPrefix: String = PREFIX
    companion object {
        const val PREFIX  = "embabel.agent.platform.models.docker"
    }
}

@ConfigurationProperties(prefix = "embabel.agent.models.docker")
class DockerConnectionProperties {
    /**
     * Base URL for Docker model endpoint
     */
    var baseUrl: String = "http://localhost:12434/engines"
}

/**
 * Docker local models
 * This class will always be loaded and models will be auto-discovered
 * from the Docker endpoint on startup. If the endpoint is unreachable,
 * discovery fails silently and no models are registered.
 * Model names will be precisely as reported from
 * http://localhost:12434/engines/v1/models (assuming default port).
 *
 * Spring AI 2.0 swapped its hand-rolled `OpenAiApi` for the openai-java SDK
 * ([OpenAIClient]). The migration removes Spring's `RestClient`/`WebClient` from
 * the OpenAI HTTP path entirely — the SDK uses OkHttp internally. Spring AI 2.0
 * also dropped the spring-retry `RetryTemplate` parameter on `OpenAiChatModel.Builder`;
 * retries are now wrapped at the ChatClientLlmOperations layer instead.
 */
@ExcludeFromJacocoGeneratedReport(reason = "Docker model configuration can't be unit tested")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    DockerRetryProperties::class,
    DockerConnectionProperties::class,
    ConfigurableModelProviderProperties::class,
    LocalModelDiscoveryProperties::class,
)
class DockerLocalModelsConfig(
    @Suppress("UNUSED_PARAMETER")
    dockerRetryProperties: DockerRetryProperties,
    private val dockerConnectionProperties: DockerConnectionProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val properties: ConfigurableModelProviderProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val localModelDiscoveryProperties: LocalModelDiscoveryProperties,
) {
    private val logger = LoggerFactory.getLogger(DockerLocalModelsConfig::class.java)

    private val discoveryFailures = DiscoveryFailureReporter(logger)

    private companion object {
        /** Connect and read budget for a model listing against a runner on this machine. */
        private val DISCOVERY_TIMEOUT = Duration.ofSeconds(2)
    }

    private data class ModelResponse(
        val `object`: String,
        val data: List<ModelDetails>,
    )

    private data class ModelDetails(
        val id: String,
    )

    private data class Model(
        val id: String,
    )

    /**
     * Shared OpenAI-compatible client pointing at the local Docker endpoint.
     * Built lazily on first model creation so a missing endpoint doesn't crash
     * Spring startup (loadModels already swallows the failure).
     */
    private val openAiClient: OpenAIClient by lazy {
        OpenAIOkHttpClient.builder()
            .baseUrl(dockerConnectionProperties.baseUrl)
            // The openai-java SDK rejects null/blank API keys even when the
            // backing server doesn't require auth. Placeholder is fine.
            .apiKey("no-auth")
            .build()
    }

    /**
     * Bounded, because this is no longer only a startup call: [DockerModelSource] makes it on the
     * path of an embedding, and an unreachable runner that accepts a connection and never answers
     * would otherwise hang that call rather than decline it.
     */
    private val discoveryClient: RestClient by lazy {
        RestClient.builder()
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(DISCOVERY_TIMEOUT)
                setReadTimeout(DISCOVERY_TIMEOUT)
            })
            .build()
    }

    private fun loadModels(): List<Model> =
        try {
            val response = discoveryClient.get()
                .uri("${dockerConnectionProperties.baseUrl}/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<ModelResponse>()

            discoveryFailures.succeeded(dockerConnectionProperties.baseUrl)
            response?.data?.map { modelDetails ->
                Model(
                    id = modelDetails.id,
                )
            } ?: emptyList()
        } catch (e: Exception) {
            discoveryFailures.failed(dockerConnectionProperties.baseUrl, e)
            emptyList()
        }


    @Bean
    fun dockerLocalModelsInitializer(): ProviderInitialization {
        logger.info("Docker local models will be discovered at {}", dockerConnectionProperties.baseUrl)

        val models = loadModels()
        logger.info(
            "Discovered the following Docker models:\n{}",
            models.joinToString("\n") { it.id })
        if (models.isEmpty()) {
            logger.warn("No Docker local models discovered. Check Docker server configuration.")
        }

        val registeredLlms = buildList {
            models.forEach { model ->
                try {
                    val beanName = "dockerModel-${model.id}"
                    val dockerModel = dockerModelOf(model)

                    // Use registerSingleton with a more descriptive bean name
                    configurableBeanFactory.registerSingleton(beanName, dockerModel)
                    add(RegisteredModel(beanName = beanName, modelId = model.id))
                    logger.debug(
                        "Successfully registered Docker {} {} as bean {}",
                        dockerModel.model!!.javaClass.simpleName,
                        model.id,
                        beanName,
                    )
                } catch (e: Exception) {
                    logger.error("Failed to register Docker model {}", model.id, e)
                }
            }
        }

        return ProviderInitialization(
            provider = PROVIDER,
            registeredLlms = registeredLlms,
        ).also { logger.info(it.summary()) }
    }

    /**
     * Docker models are open AI compatible
     */
    private fun dockerModelOf(model: Model): AiModel<*> =
        when (kindOf(model.id)) {
            LocalModelKind.EMBEDDING -> dockerEmbeddingServiceOf(model.id)
            LocalModelKind.CHAT -> dockerLlmOf(model.id)
        }

    /**
     * What a Docker model is for.
     *
     * Docker's listing does not say, so configuration does - through the shared rule, which is also
     * what startup registration here applies. One function, so a model cannot land in one category
     * at boot and the other when pulled later.
     */
    private fun kindOf(modelName: String): LocalModelKind =
        LocalModelKind.fromConfiguration(modelName, properties)

    private fun dockerEmbeddingServiceOf(modelId: String): SpringAiEmbeddingService {
        val springEmbeddingModel = OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(OpenAiEmbeddingOptions.builder()
                .model(modelId)
                .build())
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .build()

        return SpringAiEmbeddingService(
            name = modelId,
            model = springEmbeddingModel,
            provider = PROVIDER,
        )
    }

    private fun dockerLlmOf(modelId: String): SpringAiLlmService {
        val chatModel = OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .options(
                OpenAiChatOptions.builder()
                    .model(modelId)
                    .build()
            )
            .build()
        return SpringAiLlmService(
            name = modelId,
            chatModel = chatModel,
            provider = PROVIDER,
            optionsConverter = OpenAiChatOptionsConverter,
            knowledgeCutoffDate = null,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
        )
    }

    /**
     * The runner, asked per call rather than once at startup.
     *
     * [dockerLocalModelsInitializer] still registers what was there at boot, so a model named as
     * `default-embedding-model` or injected as a bean keeps working exactly as it did. This covers
     * what arrives AFTER: `docker model pull` followed by a request is enough, with no restart and
     * no distinction from a model that was already there.
     */
    private inner class DockerModelSource : LocalModelSource {

        override val provider: String = PROVIDER

        override fun servedModels(): Set<LocalModel> =
            loadModels().map { LocalModel(name = it.id, kind = kindOf(it.id)) }.toSet()

        override fun llmService(model: String) = dockerLlmOf(model)

        override fun embeddingService(model: String) = dockerEmbeddingServiceOf(model)
    }

    /**
     * The catalog and the two resolvers, built once over one [LocalModelSource].
     *
     * Lazy, so a configuration that is loaded but never asked for a model never constructs one, and
     * a field rather than a bean method because with `proxyBeanMethods = false` a bean method called
     * three times would build three catalogs and the resolvers would stop sharing a cache.
     */
    private val localModelBeans: LocalModelBeans by lazy {
        LocalModelBeans(DockerModelSource(), properties, localModelDiscoveryProperties)
    }

    @Bean
    fun dockerLocalModelCatalog(): LocalModelCatalog = localModelBeans.catalog

    @Bean
    fun dockerLocalModelRoleResolver(): LocalModelRoleResolver = localModelBeans.roleResolver

    @Bean
    fun dockerLocalModelEmbeddingRoleResolver(): LocalModelEmbeddingRoleResolver =
        localModelBeans.embeddingRoleResolver
}
