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
package com.embabel.agent.config.models.bedrock

import com.embabel.agent.api.common.RetryProperties
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.*
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.bedrock.cohere.BedrockCohereEmbeddingModel
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.observation.ChatModelObservationConvention
import org.springframework.ai.model.ModelOptionsUtils
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionConfiguration
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionProperties
import org.springframework.ai.model.bedrock.cohere.autoconfigure.BedrockCohereEmbeddingProperties
import org.springframework.ai.model.bedrock.titan.autoconfigure.BedrockTitanEmbeddingProperties
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient

/**
 * Configuration properties for Bedrock models.
 * These properties control retry behavior when calling AWS Bedrock APIs.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.bedrock")
class BedrockProperties : RetryProperties {
    /**
     * Maximum number of attempts.
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

/**
 * Configuration class for Bedrock models.
 * This class dynamically loads and registers Bedrock models from YAML configuration,
 * similar to the Anthropic configuration pattern.
 */
@Configuration(proxyBeanMethods = false)
@Import(BedrockAwsConnectionConfiguration::class)
@EnableConfigurationProperties(
    BedrockProperties::class,
    BedrockCohereEmbeddingProperties::class,
    BedrockTitanEmbeddingProperties::class
)
@ExcludeFromJacocoGeneratedReport(reason = "Bedrock configuration can't be unit tested")
class BedrockModelsConfig(
    private val properties: BedrockProperties,
    private val credentialsProvider: AwsCredentialsProvider,
    private val regionProvider: AwsRegionProvider,
    private val connectionProperties: BedrockAwsConnectionProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val observationConvention: ObjectProvider<ChatModelObservationConvention>,
    private val bedrockRuntimeClient: ObjectProvider<BedrockRuntimeClient>,
    private val bedrockRuntimeAsyncClient: ObjectProvider<BedrockRuntimeAsyncClient>,
    private val bedrockCohereEmbeddingProperties: BedrockCohereEmbeddingProperties,
    private val bedrockTitanEmbeddingProperties: BedrockTitanEmbeddingProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val modelLoader: LlmAutoConfigMetadataLoader<BedrockModelDefinitions> = BedrockModelLoader(),
) {
    private val logger = LoggerFactory.getLogger(BedrockModelsConfig::class.java)

    init {
        logger.info("Bedrock models are available: {}", properties)
    }

    @Bean
    fun bedrockModelsInitializer(): ProviderInitialization {
        val definitions = modelLoader.loadAutoConfigMetadata()

        // Register LLM models
        val registeredLlms = buildList {
            definitions.models.forEach { modelDef ->
                try {
                    val llm = createBedrockLlm(modelDef)
                    configurableBeanFactory.registerSingleton("bedrockModel-" + modelDef.name, llm)
                    add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))
                    logger.info(
                        "Registered Bedrock model bean: {} -> {} (region: {})",
                        modelDef.name, modelDef.modelId, modelDef.region
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to create model: {} ({})",
                        modelDef.name, modelDef.modelId, e
                    )
                    throw e
                }
            }
        }

        // Register embedding models
        val registeredEmbeddings = buildList {
            definitions.embeddingModels.forEach { embeddingDef ->
                try {
                    val embeddingService = createBedrockEmbedding(embeddingDef)
                    configurableBeanFactory.registerSingleton("bedrockModel-" + embeddingDef.name, embeddingService)
                    add(RegisteredModel(beanName = embeddingDef.name, modelId = embeddingDef.modelId))
                    logger.info(
                        "Registered Bedrock embedding model bean: {} -> {} (type: {})",
                        embeddingDef.name, embeddingDef.modelId, embeddingDef.modelType
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to create embedding model: {} ({})",
                        embeddingDef.name, embeddingDef.modelId, e
                    )
                    throw e
                }
            }
        }

        return ProviderInitialization(
            provider = PROVIDER,
            registeredLlms = registeredLlms,
            registeredEmbeddings = registeredEmbeddings
        ).also { logger.info(it.summary()) }
    }

    /**
     * Creates an individual Bedrock LLM from configuration.
     */
    private fun createBedrockLlm(modelDef: BedrockModelDefinition): Llm {
        val chatModel = createBedrockChatModel(modelDef.modelId)

        return Llm(
            name = modelDef.modelId,
            model = chatModel,
            provider = PROVIDER,
            optionsConverter = BedrockOptionsConverter,
            knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
            pricingModel = modelDef.pricingModel?.let {
                PerTokenPricingModel(
                    usdPer1mInputTokens = it.usdPer1mInputTokens,
                    usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                )
            }
        )
    }

    /**
     * Creates a Bedrock chat model with retry and observation support.
     */
    private fun createBedrockChatModel(modelId: String): ChatModel {
        return EmbabelBedrockProxyChatModelBuilder()
            .credentialsProvider(credentialsProvider)
            .region(regionProvider.region)
            .timeout(connectionProperties.timeout)
            .defaultOptions(BedrockChatOptions.builder().model(modelId).build())
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .toolCallingManager(
                ToolCallingManager.builder()
                    .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
                    .build()
            )
            .bedrockRuntimeClient(bedrockRuntimeClient.getIfAvailable())
            .bedrockRuntimeAsyncClient(bedrockRuntimeAsyncClient.getIfAvailable())
            .build()
            .apply<BedrockProxyChatModel> {
                observationConvention.ifAvailable(::setObservationConvention)
            }
    }

    /**
     * Creates an embedding service based on model type (Titan or Cohere).
     */
    private fun createBedrockEmbedding(embeddingDef: BedrockEmbeddingModelDefinition): EmbeddingService {
        return when (embeddingDef.modelType.lowercase()) {
            "titan" -> createTitanEmbedding(embeddingDef)
            "cohere" -> createCohereEmbedding(embeddingDef)
            else -> throw IllegalArgumentException("Unknown embedding model type: ${embeddingDef.modelType}")
        }
    }

    private fun createTitanEmbedding(embeddingDef: BedrockEmbeddingModelDefinition): EmbeddingService {
        return EmbeddingService(
            name = embeddingDef.modelId,
            model = BedrockTitanEmbeddingModel(
                TitanEmbeddingBedrockApi(
                    embeddingDef.modelId,
                    credentialsProvider,
                    regionProvider.region,
                    ModelOptionsUtils.OBJECT_MAPPER,
                    connectionProperties.timeout,
                ), observationRegistry.getIfUnique { ObservationRegistry.NOOP }
            ).withInputType(bedrockTitanEmbeddingProperties.inputType),
            provider = PROVIDER,
        )
    }

    private fun createCohereEmbedding(embeddingDef: BedrockEmbeddingModelDefinition): EmbeddingService {
        return EmbeddingService(
            name = embeddingDef.modelId,
            model = BedrockCohereEmbeddingModel(
                CohereEmbeddingBedrockApi(
                    embeddingDef.modelId,
                    credentialsProvider,
                    regionProvider.region,
                    ModelOptionsUtils.OBJECT_MAPPER,
                    connectionProperties.timeout
                ),
                bedrockCohereEmbeddingProperties.options
            ),
            provider = PROVIDER,
        )
    }

    companion object {
        const val PROVIDER = "Bedrock"

        // https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html
        const val EU_ANTHROPIC_CLAUDE_3_5_SONNET = "eu.anthropic.claude-3-5-sonnet-20240620-v1:0"
        const val EU_ANTHROPIC_CLAUDE_3_5_SONNET_V2 = "eu.anthropic.claude-3-5-sonnet-20241022-v2:0"
        const val EU_ANTHROPIC_CLAUDE_3_5_HAIKU = "eu.anthropic.claude-3-5-haiku-20241022-v1:0"
        const val EU_ANTHROPIC_CLAUDE_3_7_SONNET = "eu.anthropic.claude-3-7-sonnet-20250219-v1:0"
        const val EU_ANTHROPIC_CLAUDE_SONNET_4 = "eu.anthropic.claude-sonnet-4-20250514-v1:0"
        const val EU_ANTHROPIC_CLAUDE_OPUS_4 = "eu.anthropic.claude-opus-4-20250514-v1:0"

        const val US_ANTHROPIC_CLAUDE_3_5_SONNET = "us.anthropic.claude-3-5-sonnet-20240620-v1:0"
        const val US_ANTHROPIC_CLAUDE_3_5_SONNET_V2 = "us.anthropic.claude-3-5-sonnet-20241022-v2:0"
        const val US_ANTHROPIC_CLAUDE_3_5_HAIKU = "us.anthropic.claude-3-5-haiku-20241022-v1:0"
        const val US_ANTHROPIC_CLAUDE_3_7_SONNET = "us.anthropic.claude-3-7-sonnet-20250219-v1:0"
        const val US_ANTHROPIC_CLAUDE_SONNET_4 = "us.anthropic.claude-sonnet-4-20250514-v1:0"
        const val US_ANTHROPIC_CLAUDE_OPUS_4 = "us.anthropic.claude-opus-4-20250514-v1:0"

        const val APAC_ANTHROPIC_CLAUDE_3_5_SONNET = "apac.anthropic.claude-3-5-sonnet-20240620-v1:0"
        const val APAC_ANTHROPIC_CLAUDE_3_5_SONNET_V2 = "apac.anthropic.claude-3-5-sonnet-20241022-v2:0"
        const val APAC_ANTHROPIC_CLAUDE_3_5_HAIKU = "apac.anthropic.claude-3-5-haiku-20241022-v1:0"
        const val APAC_ANTHROPIC_CLAUDE_3_7_SONNET = "apac.anthropic.claude-3-7-sonnet-20250219-v1:0"
        const val APAC_ANTHROPIC_CLAUDE_SONNET_4 = "apac.anthropic.claude-sonnet-4-20250514-v1:0"
        const val APAC_ANTHROPIC_CLAUDE_OPUS_4 = "apac.anthropic.claude-opus-4-20250514-v1:0"
    }
}

object BedrockOptionsConverter : OptionsConverter<ToolCallingChatOptions> {
    override fun convertOptions(options: LlmOptions) =
        ToolCallingChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .maxTokens(options.maxTokens)
            .presencePenalty(options.presencePenalty)
            .frequencyPenalty(options.frequencyPenalty)
            .topK(options.topK)
            .build()
}

/**
 * Custom builder for BedrockProxyChatModel to avoid AWS configuration warning logs.
 * Inspired by org.springframework.ai.bedrock.converse.BedrockProxyChatModel.Builder.
 */
class EmbabelBedrockProxyChatModelBuilder internal constructor() {
    private var credentialsProvider: AwsCredentialsProvider? = null
    private var region: Region? = Region.US_EAST_1
    private var timeout: java.time.Duration? = java.time.Duration.ofMinutes(10)
    private var toolCallingManager: ToolCallingManager? = null
    private var toolExecutionEligibilityPredicate: ToolExecutionEligibilityPredicate =
        DefaultToolExecutionEligibilityPredicate()
    private var defaultOptions = BedrockChatOptions.builder().build()
    private var observationRegistry = ObservationRegistry.NOOP
    private var customObservationConvention: ChatModelObservationConvention? = null
    private var bedrockRuntimeClient: BedrockRuntimeClient? = null
    private var bedrockRuntimeAsyncClient: BedrockRuntimeAsyncClient? = null
    private val defaultToolCallingManager: ToolCallingManager = ToolCallingManager.builder().build()

    fun toolCallingManager(toolCallingManager: ToolCallingManager?): EmbabelBedrockProxyChatModelBuilder {
        this.toolCallingManager = toolCallingManager
        return this
    }

    fun toolExecutionEligibilityPredicate(toolExecutionEligibilityPredicate: ToolExecutionEligibilityPredicate):
            EmbabelBedrockProxyChatModelBuilder {
        this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate
        return this
    }

    fun credentialsProvider(credentialsProvider: AwsCredentialsProvider): EmbabelBedrockProxyChatModelBuilder {
        this.credentialsProvider = credentialsProvider
        return this
    }

    fun region(region: Region): EmbabelBedrockProxyChatModelBuilder {
        this.region = region
        return this
    }

    fun timeout(timeout: java.time.Duration): EmbabelBedrockProxyChatModelBuilder {
        this.timeout = timeout
        return this
    }

    fun defaultOptions(defaultOptions: BedrockChatOptions): EmbabelBedrockProxyChatModelBuilder {
        this.defaultOptions = defaultOptions
        return this
    }

    fun observationRegistry(observationRegistry: ObservationRegistry): EmbabelBedrockProxyChatModelBuilder {
        this.observationRegistry = observationRegistry
        return this
    }

    fun customObservationConvention(observationConvention: ChatModelObservationConvention): EmbabelBedrockProxyChatModelBuilder {
        this.customObservationConvention = observationConvention
        return this
    }

    fun bedrockRuntimeClient(bedrockRuntimeClient: BedrockRuntimeClient?): EmbabelBedrockProxyChatModelBuilder {
        this.bedrockRuntimeClient = bedrockRuntimeClient
        return this
    }

    fun bedrockRuntimeAsyncClient(bedrockRuntimeAsyncClient: BedrockRuntimeAsyncClient?): EmbabelBedrockProxyChatModelBuilder {
        this.bedrockRuntimeAsyncClient = bedrockRuntimeAsyncClient
        return this
    }

    fun build(): BedrockProxyChatModel {
        if (this.bedrockRuntimeClient == null) {
            this.bedrockRuntimeClient = BedrockRuntimeClient.builder()
                .region(this.region)
                .httpClientBuilder(null)
                .credentialsProvider(this.credentialsProvider)
                .overrideConfiguration { c -> c.apiCallTimeout(this.timeout) }
                .build()
        }

        if (this.bedrockRuntimeAsyncClient == null) {
            this.bedrockRuntimeAsyncClient = BedrockRuntimeAsyncClient.builder()
                .region(this.region)
                .httpClientBuilder(
                    NettyNioAsyncHttpClient.builder()
                        .tcpKeepAlive(true)
                        .connectionAcquisitionTimeout(java.time.Duration.ofSeconds(30))
                        .maxConcurrency(200)
                )
                .credentialsProvider(this.credentialsProvider)
                .overrideConfiguration { c -> c.apiCallTimeout(this.timeout) }
                .build()
        }

        return BedrockProxyChatModel(
            bedrockRuntimeClient,
            bedrockRuntimeAsyncClient,
            defaultOptions,
            observationRegistry,
            toolCallingManager ?: defaultToolCallingManager,
            toolExecutionEligibilityPredicate
        ).apply {
            if (customObservationConvention != null) {
                setObservationConvention(customObservationConvention)
            }
        }
    }
}
