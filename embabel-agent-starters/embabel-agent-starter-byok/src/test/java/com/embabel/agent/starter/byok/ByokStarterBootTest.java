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
package com.embabel.agent.starter.byok;

import com.embabel.agent.autoconfigure.models.byok.AgentByokAutoConfiguration;
import com.embabel.agent.config.models.byok.NoEmbeddingServiceConfiguredException;
import com.embabel.agent.config.models.byok.SetupRequiredEmbedding;
import com.embabel.agent.config.models.byok.SetupRequiredLlm;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.PlaceholderEmbeddingService;
import com.embabel.common.ai.model.ConfigurableModelProvider;
import com.embabel.common.ai.model.ConfigurableModelProviderProperties;
import com.embabel.common.ai.model.CredentialLlmServiceFactory;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The starter's reason to exist: an application that depends only on it can start with no
 * provider key configured at all, and the platform can still resolve a default LLM.
 * <p>
 * This runs against the starter's own dependency set, so it also guards the packaging — if a
 * provider autoconfiguration ever leaks onto the classpath it would demand a key and fail here.
 */
class ByokStarterBootTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentByokAutoConfiguration.class))
            .withPropertyValues(
                    "embabel.models.default-llm=" + SetupRequiredLlm.NAME,
                    "embabel.models.default-embedding-model=" + SetupRequiredEmbedding.NAME);

    /**
     * {@link AgentByokAutoConfiguration} orders itself before the platform autoconfiguration by
     * class NAME, because its own module deliberately does not depend on
     * platform-autoconfigure. A rename would drop that ordering silently rather than fail the
     * build, and the symptom would be the startup failure this starter exists to prevent.
     * <p>
     * This module does have platform-autoconfigure on the classpath, so the name can be pinned
     * here even though it cannot be referenced as a type where it is used.
     */
    @Test
    void theAutoConfigureBeforeTargetStillExists() throws Exception {
        var target = AgentByokAutoConfiguration.class.getAnnotation(AutoConfigureBefore.class).name();
        assertThat(target).hasSize(1);
        assertThat(Class.forName(target[0]))
                .describedAs("@AutoConfigureBefore names a class that no longer exists")
                .isNotNull();
    }

    @Test
    void startsWithNoProviderKeyConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean(SetupRequiredLlm.NAME);
        });
    }

    @Test
    void shipsCredentialFactoriesForItsProviderDependencies() {
        contextRunner.run(context -> assertThat(context.getBeansOfType(CredentialLlmServiceFactory.class))
                .containsOnlyKeys("anthropicCredentialLlmServiceFactory", "openAiCredentialLlmServiceFactory"));
    }

    @Test
    void theModelProviderResolvesTheDefaultLlmWithoutAnyKey() {
        contextRunner
                .withUserConfiguration(TestableModelProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var modelProvider = context.getBean(ModelProvider.class);
                    LlmService<?> llm = modelProvider.getLlm(DefaultModelSelectionCriteria.INSTANCE);
                    assertThat(llm.getName()).isEqualTo(SetupRequiredLlm.NAME);
                });
    }


    @Test
    void theModelProviderResolvesTheDefaultEmbeddingServiceWithoutAnyKey() {
        contextRunner
                .withUserConfiguration(TestableModelProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var embeddingService = context.getBean(ModelProvider.class)
                            .getEmbeddingService(DefaultModelSelectionCriteria.INSTANCE);
                    assertThat(embeddingService.getName()).isEqualTo(SetupRequiredEmbedding.NAME);
                    assertThat(embeddingService).isInstanceOf(PlaceholderEmbeddingService.class);
                });
    }

    /**
     * The failure this whole mechanism exists to remove, reproduced through the starter's own
     * dependency set: a consumer that resolves the default embedding service while ITS OWN bean is
     * being created. That is what RAG and memory components do, and before the placeholder the
     * resolution threw during context refresh — killing the application before any BYOK code could
     * run and before a user could type a key anywhere.
     */
    @Test
    void aConsumerResolvingEmbeddingsDuringBeanCreationNoLongerKillsTheContext() {
        contextRunner
                .withUserConfiguration(TestableModelProviderConfiguration.class, EagerEmbeddingConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EagerEmbeddingConsumer.Consumer.class).resolved)
                            .isInstanceOf(PlaceholderEmbeddingService.class);
                });
    }

    /**
     * And the half that must NOT be softened. Having something to resolve is not having a model:
     * the placeholder refuses to report a dimension, so a consumer that provisions a vector index
     * cannot build one at a guessed shape. It is expected to check the marker and skip — this pins
     * what happens to one that does not.
     */
    @Test
    void theResolvedPlaceholderStillRefusesToReportADimension() {
        contextRunner
                .withUserConfiguration(TestableModelProviderConfiguration.class)
                .run(context -> {
                    var embeddingService = context.getBean(ModelProvider.class)
                            .getEmbeddingService(DefaultModelSelectionCriteria.INSTANCE);
                    assertThatThrownBy(embeddingService::getDimensions)
                            .isInstanceOf(NoEmbeddingServiceConfiguredException.class);
                });
    }

    /**
     * A consumer that reads the marker before provisioning: it skips, and the application starts
     * with its index simply not created yet. This is the contract the marker exists for, so it is
     * worth pinning as the worked example rather than leaving it to prose.
     */
    @Test
    void aConsumerThatChecksTheMarkerSkipsProvisioningInsteadOfGuessing() {
        contextRunner
                .withUserConfiguration(TestableModelProviderConfiguration.class, IndexProvisioningConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var consumer = context.getBean(IndexProvisioningConsumer.Consumer.class);
                    assertThat(consumer.provisioned)
                            .describedAs("provisioned an index with no embedding model")
                            .isFalse();
                    assertThat(consumer.skippedBecausePlaceholder).isTrue();
                });
    }

    /**
     * The realistic BYOK configuration, and the one the tests above do NOT exercise: the operator
     * has already named the embedding model they intend to use, and simply has no key for it yet,
     * so nothing registered it. Naming the placeholder directly (as the other tests do) resolves it
     * by name and never reaches the fallback — this is the case that does.
     */
    @Test
    void startsWhenTheConfiguredEmbeddingModelIsNamedButNotYetRegistered() {
        contextRunner
                .withPropertyValues("embabel.models.default-embedding-model=text-embedding-3-small")
                .withUserConfiguration(TestableModelProviderConfiguration.class, EagerEmbeddingConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EagerEmbeddingConsumer.Consumer.class).resolved)
                            .describedAs("fell back to the placeholder rather than failing to start")
                            .isInstanceOf(PlaceholderEmbeddingService.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class EagerEmbeddingConsumer {

        static class Consumer {
            final EmbeddingService resolved;

            Consumer(ModelProvider modelProvider) {
                // The whole point: resolution happens HERE, during bean creation.
                this.resolved = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria.INSTANCE);
            }
        }

        @Bean
        Consumer eagerEmbeddingConsumer(ModelProvider modelProvider) {
            return new Consumer(modelProvider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class IndexProvisioningConsumer {

        static class Consumer {
            boolean provisioned;
            boolean skippedBecausePlaceholder;

            Consumer(ModelProvider modelProvider) {
                var embeddingService = modelProvider.getEmbeddingService(DefaultModelSelectionCriteria.INSTANCE);
                if (embeddingService instanceof PlaceholderEmbeddingService) {
                    skippedBecausePlaceholder = true;
                } else {
                    // Reading the dimension is the point: it is what a provisioner would do, and
                    // what must never happen while the service is a placeholder.
                    embeddingService.getDimensions();
                    provisioned = true;
                }
            }
        }

        @Bean
        Consumer indexProvisioningConsumer(ModelProvider modelProvider) {
            return new Consumer(modelProvider);
        }
    }

    /**
     * Builds the model provider the same way the platform autoconfiguration does — from the
     * {@code LlmService} beans present in the context — so the assertion above exercises the real
     * resolution path rather than a hand-built stand-in.
     */
    @Configuration(proxyBeanMethods = false)
    static class TestableModelProviderConfiguration {

        @Bean
        ModelProvider modelProvider(ApplicationContext applicationContext,
                                    @Value("${embabel.models.default-llm}") String defaultLlm,
                                    @Value("${embabel.models.default-embedding-model}") String defaultEmbeddingModel) {
            var properties = new ConfigurableModelProviderProperties();
            properties.setDefaultLlm(defaultLlm);
            properties.setDefaultEmbeddingModel(defaultEmbeddingModel);
            // LlmService is F-bounded (LlmService<THIS : LlmService<THIS>>), so a wildcard capture
            // from a stream will not fit the constructor. Collect through the raw type instead.
            List<LlmService<?>> llms = new ArrayList<>();
            for (LlmService<?> llm : applicationContext.getBeansOfType(LlmService.class).values()) {
                llms.add(llm);
            }
            List<EmbeddingService> embeddingServices =
                    new ArrayList<>(applicationContext.getBeansOfType(EmbeddingService.class).values());
            return new ConfigurableModelProvider(llms, embeddingServices, properties);
        }
    }
}
