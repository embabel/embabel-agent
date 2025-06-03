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

import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.embabel.common.util.loggerFor
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.observation.ChatModelObservationConvention
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionConfiguration
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionProperties
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.providers.AwsRegionProvider
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import java.time.LocalDate

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

@Configuration
@Import(BedrockAwsConnectionConfiguration::class)
@ExcludeFromJacocoGeneratedReport(reason = "Bedrock configuration can't be unit tested")
@EnableConfigurationProperties(BedrockProperties::class)
class BedrockModels(

    private val bedrockProperties: BedrockProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val environment: Environment,
    private val properties: ConfigurableModelProviderProperties,
    private val credentialsProvider: AwsCredentialsProvider,
    private val regionProvider: AwsRegionProvider,
    private val connectionProperties: BedrockAwsConnectionProperties,
    private val observationRegistry: ObjectProvider<ObservationRegistry>,
    private val observationConvention: ObjectProvider<ChatModelObservationConvention>,
    private val bedrockRuntimeClient: ObjectProvider<BedrockRuntimeClient>,
    private val bedrockRuntimeAsyncClient: ObjectProvider<BedrockRuntimeAsyncClient>,
) {
    init {
        loggerFor<BedrockModels>().info("Bedrock models are available")
    }

    private val logger = LoggerFactory.getLogger(BedrockModels::class.java)

    @PostConstruct
    fun registerModels() {
        if (!environment.activeProfiles.contains(BEDROCK_PROFILE)) {
            logger.info("Bedrock models will not be queried as the '{}' profile is not active", BEDROCK_PROFILE)
            return
        }

        if (!isBedrockConfigured(environment)) {
            logger.warn("Bedrock misconfigured, no Bedrock models available.")
            return
        }

        if (bedrockProperties.models.isEmpty()) {
            logger.warn("No Bedrock models available.")
            return
        }

        val configuredLlmModelNames = properties.llms.values.toSet() + properties.defaultLlm
        if (configuredLlmModelNames.isEmpty()) {
            logger.warn("No Bedrock models configured.")
            return
        }

        logger.info("Registering Bedrock models: {}", configuredLlmModelNames)
        bedrockProperties.models
            .filter { configuredLlmModelNames.contains(it.name) }
            .forEach { model ->
                try {
                    val beanName = "bedrockModel-${model.name.replace(":", "-").lowercase()}"
                    val llmModel = llmOf(model)
                    configurableBeanFactory.registerSingleton(beanName, llmModel)
                    logger.debug("Successfully registered Bedrock model {} as bean {}", model.name, beanName)
                } catch (e: Exception) {
                    logger.error("Failed to register Bedrock model {}: {}", model.name, e.message)
                }
            }
    }

    private fun isBedrockConfigured(environment: Environment): Boolean {
        val accessKeyId = environment.getProperty("AWS_ACCESS_KEY_ID")
        val secretAccessKey = environment.getProperty("AWS_SECRET_ACCESS_KEY")
        val region = environment.getProperty("AWS_REGION")
        if (accessKeyId == null || accessKeyId.isBlank()) {
            logger.warn("Missing required AWS_ACCESS_KEY_ID")
            return false
        }
        if (secretAccessKey == null || secretAccessKey.isBlank()) {
            logger.warn("Missing required AWS_SECRET_ACCESS_KEY")
            return false
        }
        if (region == null || region.isBlank()) {
            logger.warn("Missing required AWS_REGION")
            return false
        }
        return true
    }

    private fun llmOf(model: BedrockModelProperties): Llm = Llm(
        name = model.name,
        model = chatModelOf(model.name),
        optionsConverter = optionsConverter,
        provider = PROVIDER,
        knowledgeCutoffDate = LocalDate.parse(model.knowledgeCutoff),
        pricingModel = PerTokenPricingModel(
            usdPer1mInputTokens = model.inputPrice,
            usdPer1mOutputTokens = model.outputPrice,
        )
    )

    private fun chatModelOf(model: String): ChatModel {

        val chatModel = BedrockProxyChatModel.builder()
            .credentialsProvider(credentialsProvider)
            .region(regionProvider.region)
            .timeout(connectionProperties.timeout)
            .defaultOptions(ToolCallingChatOptions.builder().model(model).build())
            .observationRegistry(observationRegistry.getIfUnique { ObservationRegistry.NOOP })
            .bedrockRuntimeClient(bedrockRuntimeClient.getIfAvailable())
            .bedrockRuntimeAsyncClient(bedrockRuntimeAsyncClient.getIfAvailable())
            .build()

        observationConvention.ifAvailable(chatModel::setObservationConvention)

        return chatModel
    }

    private val optionsConverter: OptionsConverter = { options ->
        DefaultOptionsConverter.invoke(options)
    }

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
