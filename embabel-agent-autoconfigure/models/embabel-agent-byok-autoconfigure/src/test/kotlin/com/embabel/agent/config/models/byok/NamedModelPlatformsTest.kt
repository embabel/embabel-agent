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
package com.embabel.agent.config.models.byok

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.api.models.AtlasCloudModels
import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.autoconfigure.models.byok.AgentByokAutoConfiguration
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelPlatform
import com.embabel.common.ai.model.ModelSelectionContext
import com.embabel.common.ai.model.ModelSelectionContextHolder
import com.embabel.common.ai.model.NoSuitableModelException
import com.embabel.common.ai.model.ProviderCredential
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The shipped platforms one by one: which bean builds which provider's key, and which kinds each
 * lists. [CredentialEndpointConfigTest] asks the beans as a group; a group answer stays right when
 * the wrong bean answers, so these ask each bean by name.
 */
class NamedModelPlatformsTest {

    private companion object {

        /** Never sent anywhere: chat models are built without a request. */
        const val TEST_API_KEY = "sk-test-not-a-real-key"

        const val DOCUMENTS_ROLE = "documents"

        const val OVERRIDE_SERVICE_NAME = "from-the-application"
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AgentByokAutoConfiguration::class.java))

    private fun key(provider: String) = ProviderCredential(provider, TEST_API_KEY)

    @Nested
    inner class Kinds {

        @Test
        fun `each shipped platform lists the kinds its provider offers`() {
            contextRunner.run { context ->
                val kindsByBean = context.getBeansOfType(ModelPlatform::class.java).mapValues { it.value.kinds }
                val chatAndEmbedding = setOf(LlmService.KIND, EmbeddingService.KIND)

                assertThat(kindsByBean).containsExactlyInAnyOrderEntriesOf(
                    mapOf(
                        CredentialEndpointConfig.OPENAI_MODEL_PLATFORM to chatAndEmbedding,
                        // DeepSeek has no embeddings API.
                        CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM to setOf(LlmService.KIND),
                        CredentialEndpointConfig.MISTRAL_AI_MODEL_PLATFORM to chatAndEmbedding,
                        // Anthropic has no embeddings API.
                        CredentialEndpointConfig.ANTHROPIC_MODEL_PLATFORM to setOf(LlmService.KIND),
                        CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM to chatAndEmbedding,
                    ),
                )
            }
        }

        @Test
        fun `no shipped platform builds an embedding model for a DeepSeek key`() {
            // Each platform that lists embedding is asked, as the model provider would ask it.
            // Null from all of them, with no exception, means no request reached DeepSeek.
            contextRunner.run { context ->
                context.embeddingModelPlatforms().forEach { (bean, platform) ->
                    assertThat(platform.model(key(DeepSeekModels.PROVIDER), EmbeddingService.KIND, "some-embedding"))
                        .describedAs(bean)
                        .isNull()
                }
            }
        }

        @Test
        fun `a DeepSeek key asked for an embedding role fails with NoSuitableModelException`() {
            // The role is configured for DeepSeek, so the only reason it fails is that nothing builds the kind.
            contextRunner.run { context ->
                val modelProvider = ConfigurableModelProvider(
                    llms = listOf(SetupRequiredLlm.llmService()),
                    embeddingServices = emptyList(),
                    properties = ConfigurableModelProviderProperties(
                        defaultLlm = SetupRequiredLlm.NAME,
                        embeddingRoles = mapOf(DOCUMENTS_ROLE to mapOf(DeepSeekModels.PROVIDER to "some-embedding")),
                    ),
                    modelPlatforms = context.getBeansOfType(ModelPlatform::class.java).values.toList(),
                )

                assertThatThrownBy {
                    ModelSelectionContextHolder.with(ModelSelectionContext(credential = key(DeepSeekModels.PROVIDER))) {
                        modelProvider.getEmbeddingService(ByRoleModelSelectionCriteria(DOCUMENTS_ROLE))
                    }
                }.isInstanceOf(NoSuitableModelException::class.java)
            }
        }
    }

    @Nested
    inner class WhichBeanBuilds {

        /** The names of the beans in [this] context that return a chat model for [provider]'s key. */
        private fun ApplicationContext.chatBuildersFor(provider: String, model: String): Set<String> =
            chatModelPlatforms()
                .filterValues { it.model(key(provider), LlmService.KIND, model) != null }
                .keys

        @Test
        fun `each named provider's key is built by its own bean and no other`() {
            // One assert per provider: exactly one bean returns a model for its key, and that
            // bean is the one named for the provider.
            contextRunner.run { context ->
                assertThat(context.chatBuildersFor(OpenAiModels.PROVIDER, OpenAiModels.GPT_41_MINI))
                    .containsExactly(CredentialEndpointConfig.OPENAI_MODEL_PLATFORM)
                assertThat(context.chatBuildersFor(DeepSeekModels.PROVIDER, DeepSeekModels.DEEPSEEK_V4_FLASH))
                    .containsExactly(CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM)
                assertThat(context.chatBuildersFor(MistralAiModels.PROVIDER, MistralAiModels.MINISTRAL_8B))
                    .containsExactly(CredentialEndpointConfig.MISTRAL_AI_MODEL_PLATFORM)
                assertThat(context.chatBuildersFor(AnthropicModels.PROVIDER, AnthropicModels.CLAUDE_HAIKU_4_5))
                    .containsExactly(CredentialEndpointConfig.ANTHROPIC_MODEL_PLATFORM)
            }
        }

        @Test
        fun `a known provider with no named platform is built by the OpenAI-compatible bean`() {
            contextRunner.run { context ->
                assertThat(context.chatBuildersFor(GoogleGenAiModels.PROVIDER, GoogleGenAiModels.GEMINI_2_5_FLASH))
                    .containsExactly(CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM)
                assertThat(context.chatBuildersFor(AtlasCloudModels.PROVIDER, AtlasCloudModels.QWEN3_5_FLASH))
                    .containsExactly(CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM)
            }
        }
    }

    @Nested
    inner class Overrides {

        @Test
        fun `replacing the DeepSeek bean by name changes DeepSeek keys and leaves the other beans`() {
            contextRunner.withUserConfiguration(OwnDeepSeekPlatform::class.java).run { context ->
                val beans = context.chatModelPlatforms()
                // The same five bean names, so the application's bean replaced one rather than adding one.
                assertThat(beans).containsOnlyKeys(
                    CredentialEndpointConfig.OPENAI_MODEL_PLATFORM,
                    CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM,
                    CredentialEndpointConfig.MISTRAL_AI_MODEL_PLATFORM,
                    CredentialEndpointConfig.ANTHROPIC_MODEL_PLATFORM,
                    CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM,
                )
                // A DeepSeek key gets the application's model.
                assertThat(
                    beans.getValue(CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM)
                        .model(key(DeepSeekModels.PROVIDER), LlmService.KIND, DeepSeekModels.DEEPSEEK_V4_FLASH)?.name,
                ).isEqualTo(OVERRIDE_SERVICE_NAME)
                // The OpenAI-compatible bean still leaves DeepSeek keys to the DeepSeek bean.
                assertThat(
                    beans.getValue(CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM)
                        .model(key(DeepSeekModels.PROVIDER), LlmService.KIND, DeepSeekModels.DEEPSEEK_V4_FLASH),
                ).isNull()
                // The other named beans are the shipped ones and still build for their providers.
                assertThat(
                    beans.getValue(CredentialEndpointConfig.MISTRAL_AI_MODEL_PLATFORM)
                        .model(key(MistralAiModels.PROVIDER), LlmService.KIND, MistralAiModels.MINISTRAL_8B)?.name,
                ).isEqualTo(MistralAiModels.MINISTRAL_8B)
                assertThat(
                    beans.getValue(CredentialEndpointConfig.OPENAI_MODEL_PLATFORM)
                        .model(key(OpenAiModels.PROVIDER), LlmService.KIND, OpenAiModels.GPT_41_MINI)?.name,
                ).isEqualTo(OpenAiModels.GPT_41_MINI)
            }
        }
    }

    @Nested
    inner class OneBeanUnderSeveralNames {

        @Test
        fun `one bean registered under several names replaces each shipped bean of those names`() {
            contextRunner.withUserConfiguration(OwnPlatformUnderThreeNames::class.java).run { context ->
                val beans = context.chatModelPlatforms()
                // The aliases hide the shipped OpenAI, DeepSeek and Mistral AI beans, so three beans
                // remain: the application's one, under its first name, and two shipped ones.
                assertThat(beans).containsOnlyKeys(
                    CredentialEndpointConfig.OPENAI_MODEL_PLATFORM,
                    CredentialEndpointConfig.ANTHROPIC_MODEL_PLATFORM,
                    CredentialEndpointConfig.OPENAI_COMPATIBLE_MODEL_PLATFORM,
                )
                // Each of the three names gives the application's bean.
                val application = beans.getValue(CredentialEndpointConfig.OPENAI_MODEL_PLATFORM)
                assertThat(context.getBean(CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM)).isSameAs(application)
                assertThat(context.getBean(CredentialEndpointConfig.MISTRAL_AI_MODEL_PLATFORM)).isSameAs(application)
                // A Mistral AI key gets the application's model.
                assertThat(
                    application.model(key(MistralAiModels.PROVIDER), LlmService.KIND, MistralAiModels.MINISTRAL_8B)?.name,
                ).isEqualTo(OVERRIDE_SERVICE_NAME)
            }
        }
    }

    /** An application replacing the shipped OpenAI, DeepSeek and Mistral AI platforms with one bean. */
    @Configuration(proxyBeanMethods = false)
    class OwnPlatformUnderThreeNames {

        @Bean(
            name = [
                CredentialEndpointConfig.OPENAI_MODEL_PLATFORM,
                CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM,
                CredentialEndpointConfig.MISTRAL_AI_MODEL_PLATFORM,
            ],
        )
        fun openAiProtocolPlatform(): ModelPlatform = applicationChatPlatform(OVERRIDE_SERVICE_NAME)
    }

    /** An application replacing the shipped DeepSeek platform under its bean name. */
    @Configuration(proxyBeanMethods = false)
    class OwnDeepSeekPlatform {

        @Bean(CredentialEndpointConfig.DEEP_SEEK_MODEL_PLATFORM)
        fun deepSeekModelPlatform(): ModelPlatform =
            applicationChatPlatform(OVERRIDE_SERVICE_NAME, DeepSeekModels.PROVIDER)
    }
}
