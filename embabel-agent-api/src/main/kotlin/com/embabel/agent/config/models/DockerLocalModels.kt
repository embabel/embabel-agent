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
package com.embabel.agent.config.models

import com.embabel.agent.common.RetryProperties
import com.embabel.common.ai.model.*
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.model.NoopApiKey
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@ConfigurationProperties(prefix = "embabel.docker.models")
data class DockerProperties(
    val baseUrl: String = "http://localhost:12434/engines",
    override val maxAttempts: Int = 10,
    override val backoffMillis: Long = 2000L,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 180000L,
) : RetryProperties

/**
 * Docker local models
 * This class will always be loaded, but models won't be loaded
 * from the Docker endpoint unless the "docker" profile is set.
 * Model names will be precisely as reported from
 * http://localhost:12434/engines/v1/models (assuming default port).
 */
@ExcludeFromJacocoGeneratedReport(reason = "Docker model configuration can't be unit tested")
@Configuration(proxyBeanMethods=false)
class DockerLocalModels(
    private val dockerProperties: DockerProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val environment: Environment,
    private val properties: ConfigurableModelProviderProperties,
) {
    private val logger = LoggerFactory.getLogger(DockerLocalModels::class.java)

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
                .uri("${dockerProperties.baseUrl}/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<ModelResponse>()

            response?.data?.map { modelDetails ->
                Model(
                    id = modelDetails.id,
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load models from {}: {}", dockerProperties.baseUrl, e.message)
            emptyList()
        }


    @PostConstruct
    fun registerModels() {
        if (!environment.activeProfiles.contains(DOCKER_PROFILE)) {
            logger.info("Docker local models will not be queried as the '{}' profile is not active", DOCKER_PROFILE)
            return
        }
        logger.info("Docker local models will be discovered at {}", dockerProperties.baseUrl)

        val models = loadModels()
        logger.debug(
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
            dockerLlmOf(model)
        }
    }

    private fun dockerEmbeddingServiceOf(model: Model): EmbeddingService {
        val springEmbeddingModel = OpenAiEmbeddingModel(
            OpenAiApi.Builder()
                .baseUrl(dockerProperties.baseUrl)
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
                    .baseUrl(dockerProperties.baseUrl)
                    .apiKey(NoopApiKey())
                    .build()
            )
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(model.id)
                    .build()
            )
            .retryTemplate(dockerProperties.retryTemplate("docker-${model.id}"))
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


    companion object {
        const val DOCKER_PROFILE = "docker"

        const val PROVIDER = "Docker"
    }
}
