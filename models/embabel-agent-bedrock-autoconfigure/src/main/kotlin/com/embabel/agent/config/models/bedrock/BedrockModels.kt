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

import com.embabel.agent.config.AgentPlatformConfiguration
import com.embabel.common.ai.model.*
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.bedrock.cohere.BedrockCohereEmbeddingModel
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi.CohereEmbeddingModel
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi.TitanEmbeddingModel
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
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
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

@ConfigurationProperties(prefix = "embabel.models.bedrock")
data class BedrockProperties(
    val models: List<BedrockModelProperties> = emptyList()
)

data class BedrockModelProperties(
    val name: String = "",
    val knowledgeCutoff: String = "",
    val inputPrice: Double = 0.0,
    val outputPrice: Double = 0.0
)

@ConditionalOnClass(
    BedrockProxyChatModel::class,
    BedrockRuntimeClient::class,
    BedrockRuntimeAsyncClient::class,
    TitanEmbeddingBedrockApi::class,
    CohereEmbeddingBedrockApi::class
)
@Configuration
@Import(BedrockAwsConnectionConfiguration::class)
@EnableConfigurationProperties(
    BedrockProperties::class,
    BedrockCohereEmbeddingProperties::class,
    BedrockTitanEmbeddingProperties::class
)
@AutoConfigureBefore(AgentPlatformConfiguration::class)
class BedrockModels(

    private val bedrockProperties: BedrockProperties,
    private val credentialsProvider: AwsCredentialsProvider,
    private val regionProvider: AwsRegionProvider,
    private val connectionProperties: BedrockAwsConnectionProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val observationConvention: ObjectProvider<ChatModelObservationConvention>,
    private val bedrockRuntimeClient: ObjectProvider<BedrockRuntimeClient>,
    private val bedrockRuntimeAsyncClient: ObjectProvider<BedrockRuntimeAsyncClient>,
    private val bedrockCohereEmbeddingProperties: BedrockCohereEmbeddingProperties,
    private val bedrockTitanEmbeddingProperties: BedrockTitanEmbeddingProperties,
) {

    @Bean("bedrockModel-$EU_ANTHROPIC_CLAUDE_3_5_SONNET")
    fun euAnthropicClaude35Sonnet(): Llm = llmOf(EU_ANTHROPIC_CLAUDE_3_5_SONNET)

    @Bean("bedrockModel-$EU_ANTHROPIC_CLAUDE_3_5_SONNET_V2")
    fun euAnthropicClaude35SonnetV2(): Llm = llmOf(EU_ANTHROPIC_CLAUDE_3_5_SONNET_V2)

    @Bean("bedrockModel-$EU_ANTHROPIC_CLAUDE_3_5_HAIKU")
    fun euAnthropicClaude35Haiku(): Llm = llmOf(EU_ANTHROPIC_CLAUDE_3_5_HAIKU)

    @Bean("bedrockModel-$EU_ANTHROPIC_CLAUDE_3_7_SONNET")
    fun euAnthropicClaude37Sonnet(): Llm = llmOf(EU_ANTHROPIC_CLAUDE_3_7_SONNET)

    @Bean("bedrockModel-$EU_ANTHROPIC_CLAUDE_SONNET_4")
    fun euAnthropicClaudeSonnet4(): Llm = llmOf(EU_ANTHROPIC_CLAUDE_SONNET_4)

    @Bean("bedrockModel-$EU_ANTHROPIC_CLAUDE_OPUS_4")
    fun euAnthropicClaudeOpus4(): Llm = llmOf(EU_ANTHROPIC_CLAUDE_OPUS_4)

    @Bean("bedrockModel-$US_ANTHROPIC_CLAUDE_3_5_SONNET")
    fun usAnthropicClaude35Sonnet(): Llm = llmOf(US_ANTHROPIC_CLAUDE_3_5_SONNET)

    @Bean("bedrockModel-$US_ANTHROPIC_CLAUDE_3_5_SONNET_V2")
    fun usAnthropicClaude35SonnetV2(): Llm = llmOf(US_ANTHROPIC_CLAUDE_3_5_SONNET_V2)

    @Bean("bedrockModel-$US_ANTHROPIC_CLAUDE_3_5_HAIKU")
    fun usAnthropicClaude35Haiku(): Llm = llmOf(US_ANTHROPIC_CLAUDE_3_5_HAIKU)

    @Bean("bedrockModel-$US_ANTHROPIC_CLAUDE_3_7_SONNET")
    fun usAnthropicClaude37Sonnet(): Llm = llmOf(US_ANTHROPIC_CLAUDE_3_7_SONNET)

    @Bean("bedrockModel-$US_ANTHROPIC_CLAUDE_SONNET_4")
    fun usAnthropicClaudeSonnet4(): Llm = llmOf(US_ANTHROPIC_CLAUDE_SONNET_4)

    @Bean("bedrockModel-$US_ANTHROPIC_CLAUDE_OPUS_4")
    fun usAnthropicClaudeOpus4(): Llm = llmOf(US_ANTHROPIC_CLAUDE_OPUS_4)

    @Bean("bedrockModel-$APAC_ANTHROPIC_CLAUDE_3_5_SONNET")
    fun apacAnthropicClaude35Sonnet(): Llm = llmOf(APAC_ANTHROPIC_CLAUDE_3_5_SONNET)

    @Bean("bedrockModel-$APAC_ANTHROPIC_CLAUDE_3_5_SONNET_V2")
    fun apacAnthropicClaude35SonnetV2(): Llm = llmOf(APAC_ANTHROPIC_CLAUDE_3_5_SONNET_V2)

    @Bean("bedrockModel-$APAC_ANTHROPIC_CLAUDE_3_5_HAIKU")
    fun apacAnthropicClaude35Haiku(): Llm = llmOf(APAC_ANTHROPIC_CLAUDE_3_5_HAIKU)

    @Bean("bedrockModel-$APAC_ANTHROPIC_CLAUDE_3_7_SONNET")
    fun apacAnthropicClaude37Sonnet(): Llm = llmOf(APAC_ANTHROPIC_CLAUDE_3_7_SONNET)

    @Bean("bedrockModel-$APAC_ANTHROPIC_CLAUDE_SONNET_4")
    fun apacAnthropicClaudeSonnet4(): Llm = llmOf(APAC_ANTHROPIC_CLAUDE_SONNET_4)

    @Bean("bedrockModel-$APAC_ANTHROPIC_CLAUDE_OPUS_4")
    fun apacAnthropicClaudeOpus4(): Llm = llmOf(APAC_ANTHROPIC_CLAUDE_OPUS_4)

    @Bean("bedrockModel-amazon.titan-embed-image-v1")
    fun titanEmbedImageV1(): EmbeddingService = embeddingServiceOf(TitanEmbeddingModel.TITAN_EMBED_IMAGE_V1)

    @Bean("bedrockModel-amazon.titan-embed-text-v1")
    fun titanEmbedTextV1(): EmbeddingService = embeddingServiceOf(TitanEmbeddingModel.TITAN_EMBED_TEXT_V1)

    @Bean("bedrockModel-amazon.titan-embed-text-v2:0")
    fun titanEmbedTextV2(): EmbeddingService = embeddingServiceOf(TitanEmbeddingModel.TITAN_EMBED_TEXT_V2)

    @Bean("bedrockModel-cohere.embed-multilingual-v3")
    fun cohereEmbedMultilingualV3(): EmbeddingService = embeddingServiceOf(CohereEmbeddingModel.COHERE_EMBED_MULTILINGUAL_V3)

    @Bean("bedrockModel-cohere.embed-english-v3")
    fun cohereEmbedEnglishV3(): EmbeddingService = embeddingServiceOf(CohereEmbeddingModel.COHERE_EMBED_ENGLISH_V3)

    private fun bedrockModelProperties(string: String): BedrockModelProperties =
        bedrockProperties.models.find { it.name == string } ?: throw IllegalArgumentException("No bedrock model named $string")

    private fun llmOf(model: String): Llm = llmOf(bedrockModelProperties(model))

    private fun llmOf(model: BedrockModelProperties): Llm = Llm(
        name = model.name,
        model = chatModelOf(model.name),
        optionsConverter = BedrockOptionsConverter,
        provider = PROVIDER,
        knowledgeCutoffDate = java.time.LocalDate.parse(model.knowledgeCutoff),
        pricingModel = PerTokenPricingModel(
            usdPer1mInputTokens = model.inputPrice,
            usdPer1mOutputTokens = model.outputPrice,
        )
    )

    private fun chatModelOf(model: String): ChatModel = EmbabelBedrockProxyChatModelBuilder()
        .credentialsProvider(credentialsProvider)
        .region(regionProvider.region)
        .timeout(connectionProperties.timeout)
        .defaultOptions(ToolCallingChatOptions.builder().model(model).build())
        .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
        .bedrockRuntimeClient(bedrockRuntimeClient.getIfAvailable())
        .bedrockRuntimeAsyncClient(bedrockRuntimeAsyncClient.getIfAvailable())
        .build()
        .apply<BedrockProxyChatModel> {
            observationConvention.ifAvailable(::setObservationConvention)
        }

    private fun embeddingServiceOf(model: TitanEmbeddingModel): EmbeddingService = EmbeddingService(
        name = model.id(),
        model = BedrockTitanEmbeddingModel(
            TitanEmbeddingBedrockApi(
                model.id(),
                credentialsProvider,
                regionProvider.region,
                ModelOptionsUtils.OBJECT_MAPPER,
                connectionProperties.timeout,
            ), observationRegistry.getIfUnique { ObservationRegistry.NOOP }
        ).withInputType(bedrockTitanEmbeddingProperties.inputType),
        provider = PROVIDER,
    )

    private fun embeddingServiceOf(model: CohereEmbeddingModel): EmbeddingService = EmbeddingService(
        name = model.id(),
        model = BedrockCohereEmbeddingModel(
            CohereEmbeddingBedrockApi(
                model.id(),
                credentialsProvider,
                regionProvider.region,
                ModelOptionsUtils.OBJECT_MAPPER,
                connectionProperties.timeout
            ),
            bedrockCohereEmbeddingProperties.options
        ),
        provider = PROVIDER,
    )

    companion object {

        const val BEDROCK_PROFILE = "bedrock"

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

        const val PROVIDER = "Bedrock"
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
            .topP(options.topP)
            .build()
}

/**
 * Inspired from final org.springframework.ai.bedrock.converse.BedrockProxyChatModel.Builder class to avoid annoying
 * warn log message during builder initialization relative to how AWS configuration values are provided.
 */
class EmbabelBedrockProxyChatModelBuilder internal constructor() {
    private var credentialsProvider: AwsCredentialsProvider? = null
    private var region: Region? = Region.US_EAST_1
    private var timeout: java.time.Duration? = java.time.Duration.ofMinutes(10)
    private var toolCallingManager: ToolCallingManager? = null
    private var toolExecutionEligibilityPredicate: ToolExecutionEligibilityPredicate =
        DefaultToolExecutionEligibilityPredicate()
    private var defaultOptions = ToolCallingChatOptions.builder().build()
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

    fun defaultOptions(defaultOptions: ToolCallingChatOptions): EmbabelBedrockProxyChatModelBuilder {
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
