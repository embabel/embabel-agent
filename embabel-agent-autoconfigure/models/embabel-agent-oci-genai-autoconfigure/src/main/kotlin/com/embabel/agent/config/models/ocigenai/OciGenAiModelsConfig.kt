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
package com.embabel.agent.config.models.ocigenai

import com.embabel.agent.api.models.OciGenAiModels
import com.embabel.agent.config.models.ocigenai.OciGenAiProperties.Companion.PREFIX
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.model.SpringAiEmbeddingService
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.fasterxml.jackson.databind.ObjectMapper
import com.oracle.bmc.Region
import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider
import com.oracle.bmc.auth.SessionTokenAuthenticationDetailsProvider
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider
import com.oracle.bmc.auth.okeworkloadidentity.OkeWorkloadIdentityAuthenticationDetailsProvider
import com.oracle.bmc.generativeaiinference.GenerativeAiInference
import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths

@ConfigurationProperties(prefix = PREFIX)
class OciGenAiProperties : RetryProperties {

    var authenticationType: AuthenticationType = AuthenticationType.FILE
    var configFile: String? = null
    var profile: String? = null
    var federationEndpoint: String? = null
    var tenantId: String? = null
    var userId: String? = null
    var fingerprint: String? = null
    var privateKey: String? = null
    var privateKeyFile: String? = null
    var passPhrase: String? = null
    var sessionToken: String? = null
    var sessionTokenFile: String? = null
    var workloadIdentityTokenPath: String? = null
    var region: String? = null
    var endpoint: String? = null
    var compartmentId: String? = null
    var servingMode: OciGenAiServingMode = OciGenAiServingMode.ON_DEMAND
    var endpointId: String? = null

    override var maxAttempts: Int = 10
    override var backoffMillis: Long = 5_000L
    override var backoffMultiplier: Double = 5.0
    override var backoffMaxInterval: Long = 180_000L

    override val propertyPrefix: String = PREFIX
    companion object {
        const val PREFIX  = "embabel.agent.platform.models.ocigenai"
    }

    override fun toString(): String =
        "OciGenAiProperties(" +
            "authenticationType=$authenticationType, " +
            "configFile=$configFile, " +
            "profile=$profile, " +
            "federationEndpoint=$federationEndpoint, " +
            "tenantId=$tenantId, " +
            "userId=$userId, " +
            "fingerprint=${fingerprint.masked()}, " +
            "privateKey=${privateKey.masked()}, " +
            "privateKeyFile=$privateKeyFile, " +
            "passPhrase=${passPhrase.masked()}, " +
            "sessionToken=${sessionToken.masked()}, " +
            "sessionTokenFile=$sessionTokenFile, " +
            "workloadIdentityTokenPath=$workloadIdentityTokenPath, " +
            "region=$region, " +
            "endpoint=$endpoint, " +
            "compartmentId=$compartmentId, " +
            "servingMode=$servingMode, " +
            "endpointId=$endpointId, " +
            "maxAttempts=$maxAttempts, " +
            "backoffMillis=$backoffMillis, " +
            "backoffMultiplier=$backoffMultiplier, " +
            "backoffMaxInterval=$backoffMaxInterval" +
            ")"

    enum class AuthenticationType {
        FILE,
        INSTANCE_PRINCIPAL,
        RESOURCE_PRINCIPAL,
        WORKLOAD_IDENTITY,
        SIMPLE,
        SESSION_TOKEN,
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OciGenAiProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "OCI GenAI client configuration can't be unit tested")
class OciGenAiClientConfig(
    private val properties: OciGenAiProperties,
) {

    @Bean
    @ConditionalOnMissingBean(AbstractAuthenticationDetailsProvider::class)
    fun ociGenAiAuthenticationProvider(): AbstractAuthenticationDetailsProvider =
        when (properties.authenticationType) {
            OciGenAiProperties.AuthenticationType.FILE -> ConfigFileAuthenticationDetailsProvider(
                expandHome(properties.configFile ?: DEFAULT_OCI_CONFIG_FILE),
                properties.profile ?: DEFAULT_OCI_PROFILE,
            )

            OciGenAiProperties.AuthenticationType.INSTANCE_PRINCIPAL ->
                InstancePrincipalsAuthenticationDetailsProvider.builder()
                    .apply { properties.federationEndpoint?.let { federationEndpoint(it) } }
                    .build()

            OciGenAiProperties.AuthenticationType.RESOURCE_PRINCIPAL ->
                ResourcePrincipalAuthenticationDetailsProvider.builder()
                    .apply { properties.federationEndpoint?.let { resourcePrincipalSessionTokenEndpoint(it) } }
                    .build()

            OciGenAiProperties.AuthenticationType.WORKLOAD_IDENTITY ->
                OkeWorkloadIdentityAuthenticationDetailsProvider.builder()
                    .apply {
                        properties.tenantId?.let { tenancyId(it) }
                        properties.region?.let { region(Region.fromRegionId(it)) }
                        properties.workloadIdentityTokenPath?.let { tokenPath(it) }
                    }
                    .build()

            OciGenAiProperties.AuthenticationType.SIMPLE ->
                SimpleAuthenticationDetailsProvider.builder()
                    .tenantId(requireProperty(properties.tenantId, "tenant-id"))
                    .userId(requireProperty(properties.userId, "user-id"))
                    .fingerprint(requireProperty(properties.fingerprint, "fingerprint"))
                    .privateKeySupplier {
                        properties.privateKeyFile?.let { Files.newInputStream(Paths.get(expandHome(it))) }
                            ?: ByteArrayInputStream(requireProperty(properties.privateKey, "private-key").toByteArray())
                    }
                    .apply {
                        properties.passPhrase?.let { passPhrase(it) }
                        properties.region?.let { region(Region.fromRegionId(it)) }
                    }
                    .build()

            OciGenAiProperties.AuthenticationType.SESSION_TOKEN ->
                properties.configFile?.let { configFile ->
                    SessionTokenAuthenticationDetailsProvider(
                        expandHome(configFile),
                        properties.profile ?: DEFAULT_OCI_PROFILE,
                    )
                } ?: run {
                    SessionTokenAuthenticationDetailsProvider.builder()
                        .tenantId(requireProperty(properties.tenantId, "tenant-id"))
                        .userId(requireProperty(properties.userId, "user-id"))
                        .fingerprint(requireProperty(properties.fingerprint, "fingerprint"))
                        .apply {
                            properties.privateKeyFile?.let { privateKeyFilePath(expandHome(it)) }
                            properties.passPhrase?.let { passPhrase(it) }
                            properties.sessionToken?.let { sessionToken(it) }
                            properties.sessionTokenFile?.let { sessionTokenFilePath(expandHome(it)) }
                            properties.region?.let { region(it) }
                        }
                        .build()
                }
        }

    @Bean
    @ConditionalOnMissingBean
    fun ociGenAiInferenceClient(
        authenticationDetailsProvider: AbstractAuthenticationDetailsProvider,
    ): GenerativeAiInference {
        val client = GenerativeAiInferenceClient.builder().build(authenticationDetailsProvider)
        properties.endpoint?.let { client.setEndpoint(it) }
        if (properties.endpoint == null) {
            properties.region?.let { client.setRegion(it) }
        }
        return client
    }

    private fun requireProperty(value: String?, name: String): String =
        requireNotNull(value?.takeIf { it.isNotBlank() }) {
            "OCI GenAI $name is required for ${properties.authenticationType} authentication"
        }

    private fun expandHome(path: String): String =
        if (path.startsWith("~/")) {
            Paths.get(System.getProperty("user.home"), path.removePrefix("~/")).toString()
        } else {
            path
        }

    companion object {
        private const val DEFAULT_OCI_CONFIG_FILE = "~/.oci/config"
        private const val DEFAULT_OCI_PROFILE = "DEFAULT"
    }
}

private fun String?.masked(): String =
    if (this.isNullOrBlank()) {
        "null"
    } else {
        "<masked>"
    }

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OciGenAiProperties::class)
@ExcludeFromJacocoGeneratedReport(reason = "OCI GenAI model configuration can't be unit tested")
class OciGenAiModelsConfig(
    private val properties: OciGenAiProperties,
    private val client: ObjectProvider<GenerativeAiInference>,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val objectMapper: ObjectProvider<ObjectMapper>,
    private val modelLoader: LlmAutoConfigMetadataLoader<OciGenAiModelDefinitions> = OciGenAiModelLoader(),
) {
    private val logger = LoggerFactory.getLogger(OciGenAiModelsConfig::class.java)

    init {
        logger.info("OCI GenAI models are available: {}", properties)
    }

    @Bean
    fun ociGenAiModelsInitializer(): ProviderInitialization {
        val definitions = modelLoader.loadAutoConfigMetadata()

        val registeredLlms = buildList {
            definitions.models.forEach { modelDef ->
                try {
                    val llm = createOciGenAiLlm(modelDef)
                    configurableBeanFactory.registerSingleton(modelDef.name, llm)
                    add(RegisteredModel(beanName = modelDef.name, modelId = modelDef.modelId))
                    logger.info("Registered OCI GenAI model bean: {} -> {}", modelDef.name, modelDef.modelId)
                } catch (e: Exception) {
                    logger.error(
                        "Failed to create OCI GenAI model: {} ({})",
                        modelDef.name, modelDef.modelId, e
                    )
                    throw e
                }
            }
        }

        val registeredEmbeddings = buildList {
            definitions.embeddingModels.forEach { embeddingDef ->
                try {
                    val embeddingService = createOciGenAiEmbedding(embeddingDef)
                    configurableBeanFactory.registerSingleton(embeddingDef.name, embeddingService)
                    add(RegisteredModel(beanName = embeddingDef.name, modelId = embeddingDef.modelId))
                    logger.info(
                        "Registered OCI GenAI embedding model bean: {} -> {}",
                        embeddingDef.name,
                        embeddingDef.modelId,
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Failed to create OCI GenAI embedding model: {} ({})",
                        embeddingDef.name, embeddingDef.modelId, e
                    )
                    throw e
                }
            }
        }

        return ProviderInitialization(
            provider = OciGenAiModels.PROVIDER,
            registeredLlms = registeredLlms,
            registeredEmbeddings = registeredEmbeddings,
        ).also { logger.info(it.summary()) }
    }

    private fun createOciGenAiLlm(modelDef: OciGenAiModelDefinition): LlmService<*> {
        val chatModel = OciGenAiChatModel(
            client = client.getObject(),
            defaultOptions = createDefaultOptions(modelDef),
            retryTemplate = properties.retryTemplate("oci-genai-${modelDef.modelId}"),
            objectMapper = objectMapper.getIfAvailable { ObjectMapper() },
        )
        return SpringAiLlmService(
            name = modelDef.modelId,
            chatModel = chatModel,
            provider = OciGenAiModels.PROVIDER,
            optionsConverter = OciGenAiOptionsConverter,
            knowledgeCutoffDate = modelDef.knowledgeCutoffDate,
            pricingModel = modelDef.pricingModel?.let {
                PerTokenPricingModel(
                    usdPer1mInputTokens = it.usdPer1mInputTokens,
                    usdPer1mOutputTokens = it.usdPer1mOutputTokens,
                )
            },
        )
    }

    private fun createDefaultOptions(modelDef: OciGenAiModelDefinition): OciGenAiChatOptions =
        OciGenAiChatOptions.builder()
            .model(modelDef.modelId)
            .compartmentId(properties.compartmentId)
            .servingMode(properties.servingMode)
            .endpointId(properties.endpointId)
            .apiFormat(modelDef.apiFormat)
            .maxTokens(modelDef.maxTokens)
            .temperature(modelDef.temperature)
            .topP(modelDef.topP)
            .topK(modelDef.topK)
            .build()

    private fun createOciGenAiEmbedding(embeddingDef: OciGenAiEmbeddingModelDefinition): EmbeddingService {
        val embeddingModel = OciGenAiEmbeddingModel(
            client = client.getObject(),
            options = OciGenAiEmbeddingOptions(
                model = embeddingDef.modelId,
                compartmentId = requireNotNull(properties.compartmentId) {
                    "OCI GenAI compartment-id is required to configure embedding model ${embeddingDef.name}"
                },
                servingMode = properties.servingMode,
                endpointId = properties.endpointId,
                dimensions = embeddingDef.dimensions,
                truncate = embeddingDef.truncate,
                inputType = embeddingDef.inputType,
            ),
            retryTemplate = properties.retryTemplate("oci-genai-embedding-${embeddingDef.modelId}"),
        )
        val pricing = embeddingDef.pricingModel?.let {
            PricingModel.usdPer1MTokens(it.usdPer1mTokens, 0.0)
        }
        return SpringAiEmbeddingService(
            name = embeddingDef.modelId,
            model = embeddingModel,
            provider = OciGenAiModels.PROVIDER,
            configuredDimensions = embeddingDef.dimensions,
            pricingModel = pricing,
        )
    }
}

object OciGenAiOptionsConverter : OptionsConverter<OciGenAiChatOptions> {

    override fun convertOptions(options: LlmOptions): OciGenAiChatOptions =
        OciGenAiChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .topK(options.topK)
            .maxTokens(options.maxTokens)
            .frequencyPenalty(options.frequencyPenalty)
            .presencePenalty(options.presencePenalty)
            .internalToolExecutionEnabled(false)
            .build()
}
