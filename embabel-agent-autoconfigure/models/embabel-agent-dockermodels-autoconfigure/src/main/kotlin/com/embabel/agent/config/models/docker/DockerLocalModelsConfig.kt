/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.common.RetryProperties
import com.embabel.agent.config.models.DockerLocalModels.Companion.PROVIDER
import com.embabel.agent.config.models.OpenAiChatOptionsConverter
import com.embabel.common.ai.model.*
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.model.NoopApiKey
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.reactive.function.client.WebClient


@ConfigurationProperties(prefix = "embabel.agent.platform.models.docker")
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
 * This class will always be loaded, but models won't be loaded
 * from the Docker endpoint unless the "docker" profile is set.
 * Model names will be precisely as reported from
 * http://localhost:12434/engines/v1/models (assuming default port).
 */
@ExcludeFromJacocoGeneratedReport(reason = "Docker model configuration can't be unit tested")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    DockerRetryProperties::class,
    DockerConnectionProperties::class,
    ConfigurableModelProviderProperties::class
)
class DockerLocalModelsConfig(
    private val dockerRetryProperties: DockerRetryProperties,
    private val dockerConnectionProperties: DockerConnectionProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val properties: ConfigurableModelProviderProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
) {
    private val logger = LoggerFactory.getLogger(DockerLocalModelsConfig::class.java)

    private data class ModelResponse(
        val `object`: String,
        val data: List<ModelDetails>
    )

    private data class ModelDetails(
        val id: String,
    )

    private data class Model(
        val id: String
    )

    private fun loadModels(): List<Model> =
        try {
            val restClient = RestClient.create()
            val response = restClient.get()
                .uri("${dockerConnectionProperties.baseUrl}/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<ModelResponse>()

            response?.data?.map { modelDetails ->
                Model(
                    id = modelDetails.id,
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load models from {}: {}", dockerConnectionProperties.baseUrl, e.message)
            emptyList()
        }


    @PostConstruct
    fun registerModels() {
        logger.info("Docker local models will be discovered at {}", dockerConnectionProperties.baseUrl)

        val models = loadModels()
        logger.info(
            "Discovered the following Docker models:\n{}",
            models.joinToString("\n") { it.id })
        if (models.isEmpty()) {
            logger.warn("No Docker local models discovered. Check Docker server configuration.")
            return
        }

        models.forEach { model ->
            try {
                val beanName = "dockerModel-${model.id}"
                val dockerModel = dockerModelOf(model)

                // Use registerSingleton with a more descriptive bean name
                configurableBeanFactory.registerSingleton(beanName, dockerModel)
                logger.debug(
                    "Successfully registered Docker {} {} as bean {}",
                    dockerModel.model.javaClass.simpleName,
                    model.id,
                    beanName,
                )
            } catch (e: Exception) {
                logger.error("Failed to register Docker model {}", model.id, e)
            }
        }
    }

    /**
     * Docker models are open AI compatible
     */
    private fun dockerModelOf(model: Model): AiModel<*> {
        return if (properties.allWellKnownEmbeddingServiceNames().contains(model.id)) {
            dockerEmbeddingServiceOf(model)
        } else {
            return dockerLlmOf(model)
        }
    }

    private fun dockerEmbeddingServiceOf(model: Model): EmbeddingService {
        val springEmbeddingModel = OpenAiEmbeddingModel(
            OpenAiApi.Builder()
                .baseUrl(dockerConnectionProperties.baseUrl)
                .apiKey(NoopApiKey())
                .build(),
            MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder()
                .model(model.id)
                .build(),
        )

        return EmbeddingService(
            name = model.id,
            model = springEmbeddingModel,
            provider = PROVIDER,
        )
    }

    private fun dockerLlmOf(model: Model): Llm {
        val chatModel = OpenAiChatModel.builder()
            .openAiApi(
                OpenAiApi.Builder()
                    .baseUrl(dockerConnectionProperties.baseUrl)
                    .apiKey(NoopApiKey())
                    .restClientBuilder(RestClient.builder()
                        .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
                    .webClientBuilder(WebClient.builder()
                        .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP }))
                    .build()
            )
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .toolCallingManager(ToolCallingManager.builder()
                .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build())
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(model.id)
                    .build()
            )
            .retryTemplate(dockerRetryProperties.retryTemplate("docker-${model.id}"))
            .build()
        return Llm(
            name = model.id,
            model = chatModel,
            provider = PROVIDER,
            optionsConverter = OpenAiChatOptionsConverter,
            knowledgeCutoffDate = null,
            pricingModel = PricingModel.ALL_YOU_CAN_EAT,
        )
    }

}
