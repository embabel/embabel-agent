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
package com.embabel.agent.autoconfigure.models.byok;

import com.embabel.agent.api.models.AnthropicModels;
import com.embabel.agent.api.models.OpenAiModels;
import com.embabel.agent.config.models.byok.SetupRequiredEmbedding;
import com.embabel.agent.config.models.byok.SetupRequiredLlm;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.PlaceholderEmbeddingService;
import com.embabel.common.ai.model.CredentialLlmServiceFactory;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.ProviderCredential;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentByokAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentByokAutoConfiguration.class));

    @Test
    void registersThePlaceholderWithNoApiKeyConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean(SetupRequiredLlm.NAME);
            assertThat(context.getBean(SetupRequiredLlm.NAME)).isInstanceOf(LlmService.class);
        });
    }

    @Test
    void placeholderIsTheOnlyLlmServiceContributed() {
        contextRunner.run(context ->
                assertThat(context.getBeansOfType(LlmService.class)).containsOnlyKeys(SetupRequiredLlm.NAME));
    }

    /**
     * The embedding placeholder is a separate {@code @Import} on the autoconfiguration, and the
     * assertions above pass with or without it: an {@link EmbeddingService} is not an
     * {@link LlmService}, so dropping the import would leave every other test in this module green
     * while a BYOK app using RAG went back to failing its context refresh.
     */
    @Test
    void registersTheEmbeddingPlaceholderWithNoApiKeyConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean(SetupRequiredEmbedding.NAME);
            assertThat(context.getBean(SetupRequiredEmbedding.NAME))
                    .isInstanceOf(EmbeddingService.class)
                    .isInstanceOf(PlaceholderEmbeddingService.class);
        });
    }

    /**
     * Registered under the well-known name, because that is the value an operator puts in
     * {@code embabel.models.default-embedding-model} and what the model provider matches against.
     */
    @Test
    void embeddingPlaceholderIsTheOnlyEmbeddingServiceContributed() {
        contextRunner.run(context ->
                assertThat(context.getBeansOfType(EmbeddingService.class))
                        .containsOnlyKeys(SetupRequiredEmbedding.NAME));
    }

    @Test
    void registersCredentialFactoriesForProvidersOnTheClasspath() {
        contextRunner.run(context -> assertThat(context.getBeansOfType(CredentialLlmServiceFactory.class))
                .containsOnlyKeys("anthropicCredentialLlmServiceFactory", "openAiCredentialLlmServiceFactory"));
    }

    @Test
    void credentialFactoriesOnlyHandleTheirProvider() {
        contextRunner.run(context -> {
            var factories = context.getBeansOfType(CredentialLlmServiceFactory.class);
            var anthropic = factories.get("anthropicCredentialLlmServiceFactory");
            var openAi = factories.get("openAiCredentialLlmServiceFactory");
            var anthropicService = anthropic.createLlmService(
                    new ProviderCredential("ANTHROPIC", "key"), "claude-test");
            var openAiService = openAi.createLlmService(
                    new ProviderCredential("OPENAI", "key"), "gpt-test");

            assertThat(anthropicService).isNotNull();
            assertThat(anthropicService.getProvider()).isEqualTo(AnthropicModels.PROVIDER);
            assertThat(anthropic.createLlmService(new ProviderCredential("openai", "key"), "gpt-test"))
                    .isNull();
            assertThat(openAiService).isNotNull();
            assertThat(openAiService.getProvider()).isEqualTo(OpenAiModels.PROVIDER);
            assertThat(openAi.createLlmService(new ProviderCredential("anthropic", "key"), "claude-test"))
                    .isNull();
        });
    }

    @Test
    void applicationFactoryWithTheProviderBeanNameWins() {
        CredentialLlmServiceFactory custom = (credential, model) -> null;

        contextRunner
                .withBean("openAiCredentialLlmServiceFactory", CredentialLlmServiceFactory.class, () -> custom)
                .run(context -> assertThat(context.getBean("openAiCredentialLlmServiceFactory"))
                        .isSameAs(custom));
    }
}
