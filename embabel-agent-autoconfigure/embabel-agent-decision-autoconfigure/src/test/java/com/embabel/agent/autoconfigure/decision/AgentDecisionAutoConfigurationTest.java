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
package com.embabel.agent.autoconfigure.decision;

import com.embabel.agent.decision.CallFailure;
import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionModelInitialization;
import com.embabel.agent.decision.DecisionOutcome;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.NoDecisionModel;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.config.spring.AgentPlatformConfiguration;
import com.embabel.agent.spi.loop.LlmMessageResponse;
import com.embabel.agent.spi.loop.LlmMessageSender;
import com.embabel.chat.Message;
import com.embabel.common.ai.autoconfig.ProviderInitialization;
import com.embabel.common.ai.model.AutoModelSelectionCriteria;
import com.embabel.common.ai.model.ByNameModelSelectionCriteria;
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria;
import com.embabel.common.ai.model.ConfigurableModelProviderProperties;
import com.embabel.common.ai.model.DecisionModelMetadata;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.FallbackByNameModelSelectionCriteria;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.ModelSelectionCriteria;
import com.embabel.common.ai.model.RandomByNameModelSelectionCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDecisionAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentDecisionAutoConfiguration.class));

    @Test
    void classpathIsInertUntilExplicitlyEnabled() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(DecisionModel.class)
                .doesNotHaveBean(DecisionModelInitialization.class));
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=false",
                        "embabel.agent.decision.models.disabled.provider=stub")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DecisionModel.class)
                        .doesNotHaveBean(DecisionModelInitialization.class));
    }

    @Test
    void registersTwoNamedModelsAfterProviderInitializationAndIntegratesCustomBeans() {
        LlmService<?> promptedService = service(
                "backend-name",
                "{\"answers\":[{\"keyId\":\"q\",\"kind\":\"YES_NO\",\"pTrue\":0.75}]}");
        DecisionModel custom = NoDecisionModel.create().named("custom", "user");

        integratedRunner(promptedService)
                .withBean("custom", DecisionModel.class, () -> custom)
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.proposition-revision.provider=none",
                        "embabel.agent.decision.models.prompted-review.provider=prompted",
                        "embabel.agent.decision.models.prompted-review.prompted.llm-bean-name=dynamicLlm",
                        "embabel.agent.decision.models.prompted-review.prompted.options-bean-name=decisionOptions",
                        "embabel.models.default-llm=backend-name",
                        "embabel.models.decisions.revision=proposition-revision",
                        "embabel.models.default-decision-model=prompted-review")
                .run(context -> {
                    assertThat(context).hasBean("proposition-revision").hasBean("prompted-review").hasBean("custom");
                    assertThat(context).doesNotHaveBean("jev");
                    assertThat(context.getBeansOfType(DecisionModel.class)).hasSize(3);
                    assertThat(context.getBean("proposition-revision", DecisionModel.class).getName())
                            .isEqualTo("proposition-revision");
                    assertThat(context.getBean("proposition-revision", DecisionModel.class).getProvider())
                            .isEqualTo("none");
                    assertThat(context.getBean("prompted-review", DecisionModel.class).getName())
                            .isEqualTo("prompted-review");
                    assertThat(context.getBean("prompted-review", DecisionModel.class).getProvider())
                            .isEqualTo("prompted");

                    ModelProvider models = context.getBean(ModelProvider.class);
                    assertThat(models.getDecisionModel(new ByRoleModelSelectionCriteria("revision")).getName())
                            .isEqualTo("proposition-revision");
                    assertThat(models.getDecisionModel(new ByNameModelSelectionCriteria("custom"))).isSameAs(custom);
                    assertThat(models.getDecisionModel(new FallbackByNameModelSelectionCriteria(
                            List.of("missing", "prompted-review"))).getName()).isEqualTo("prompted-review");
                    assertThat(models.getDecisionModel(DefaultModelSelectionCriteria.INSTANCE).getName())
                            .isEqualTo("prompted-review");
                    assertThat(models.getDecisionModel(AutoModelSelectionCriteria.INSTANCE).getName())
                            .isEqualTo("prompted-review");
                    assertThat(models.getDecisionModel(new RandomByNameModelSelectionCriteria(List.of("custom"))))
                            .isSameAs(custom);
                    assertThat(models.getDecisionModel(ModelSelectionCriteria.preResolved(custom))).isSameAs(custom);
                    assertThat(models.listModels().stream()
                            .filter(DecisionModelMetadata.class::isInstance)
                            .map(model -> model.getName() + ":" + model.getProvider()))
                            .containsExactlyInAnyOrder(
                                    "proposition-revision:none", "prompted-review:prompted", "custom:user");

                    DecisionModelInitialization receipt = context.getBean(DecisionModelInitialization.class);
                    assertThat(receipt.getCreatedModels())
                            .extracting(DecisionModel::getName)
                            .containsExactly("prompted-review", "proposition-revision");
                });
        verify(promptedService, never()).createMessageSender(any(LlmOptions.class));
    }

    @Test
    void initializationReceiptClosesOnlyConfiguredFacadesOnce() {
        LlmService<?> promptedService = service(
                "backend-name",
                "{\"answers\":[{\"keyId\":\"q\",\"kind\":\"YES_NO\",\"pTrue\":0.75}]}");
        DecisionModel custom = NoDecisionModel.create().named("custom", "user");

        integratedRunner(promptedService)
                .withBean("custom", DecisionModel.class, () -> custom)
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.prompted-review.provider=prompted",
                        "embabel.agent.decision.models.prompted-review.prompted.llm-bean-name=dynamicLlm",
                        "embabel.models.default-llm=backend-name",
                        "embabel.models.default-decision-model=prompted-review")
                .run(context -> {
                    DecisionModel configured = context.getBean("prompted-review", DecisionModel.class);
                    assertThat(configured.ask(request())).isInstanceOf(DecisionOutcome.Success.class);
                    DecisionModelInitialization receipt = context.getBean(DecisionModelInitialization.class);
                    assertThat(receipt.getCreatedModels()).containsExactly(configured).doesNotContain(custom);
                    receipt.close();
                    receipt.close();
                    assertThat(configured.ask(request())).isInstanceOf(DecisionOutcome.Failure.class);
                    assertThat(custom.ask(request())).isInstanceOf(DecisionOutcome.Failure.class);
                    verify(promptedService, atLeastOnce()).createMessageSender(any(LlmOptions.class));
                });
    }

    @Test
    void enabledWithoutDeclarationsSynthesizesJevAsThePlatformDefault() {
        integratedRunner(service("backend-name", "{}"))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.models.default-llm=backend-name")
                .run(context -> {
                    DecisionModel jev = context.getBean("jev", DecisionModel.class);
                    assertThat(jev.getName()).isEqualTo("jev");
                    assertThat(jev.getProvider()).isEqualTo("typesafe");
                    DecisionProperties.Model configured = context.getBean(DecisionProperties.class)
                            .models().get("jev");
                    assertThat(configured.provider()).isEqualTo("typesafe");
                    assertThat(configured.typesafe().model()).isEqualTo("jev-latest");
                    assertThat(context.getBean(DecisionModelInitialization.class).getCreatedModels())
                            .containsExactly(jev);
                    assertThat(context.getBean(ModelProvider.class)
                            .getDecisionModel(DefaultModelSelectionCriteria.INSTANCE)).isSameAs(jev);
                });

        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models=")
                .run(context -> assertThat(context.getBean("jev", DecisionModel.class).getProvider())
                        .isEqualTo("typesafe"));
    }

    @Test
    void environmentVariableDeclarationReplacesImplicitJev() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("EMBABEL_AGENT_DECISION_MODELS_FOO_PROVIDER", "none"))))
                .withPropertyValues("embabel.agent.decision.enabled=true")
                .run(context -> assertThat(context)
                        .hasBean("foo")
                        .doesNotHaveBean("jev"));
    }

    @Test
    void legacyEnvironmentVariableIsRejected() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("EMBABEL_AGENT_DECISION_PROVIDER", "none"))))
                .withPropertyValues("embabel.agent.decision.enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.provider"));
    }

    @Test
    void relaxedPropertySpellingsAreCanonicalizedBeforeValidation() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource(
                                "relaxed",
                                Map.of(
                                        "embabel.agent.decision.models.rules.provider", "typesafe",
                                        "embabel.agent.decision.models.rules.typesafe.model", "jev-latest",
                                        "embabel.agent.decision.models.rules.typesafe.connectTimeout", "11s"))))
                .withPropertyValues("embabel.agent.decision.enabled=true")
                .run(context -> assertThat(context.getBean(DecisionProperties.class)
                                .models().get("rules").typesafe().connectTimeout())
                        .hasSeconds(11));
    }

    @Test
    void disabledDecisionAutoconfigurationLeavesAUserOnlySharedProvider() {
        DecisionModel custom = NoDecisionModel.create().named("custom", "user");
        integratedRunner(service("backend-name", "{}"))
                .withBean("custom", DecisionModel.class, () -> custom)
                .withPropertyValues(
                        "embabel.agent.decision.enabled=false",
                        "embabel.models.default-llm=backend-name")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DecisionModelInitialization.class).doesNotHaveBean("jev");
                    assertThat(context.getBean(ModelProvider.class)
                            .getDecisionModel(DefaultModelSelectionCriteria.INSTANCE)).isSameAs(custom);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "embabel.agent.decision.provider=none",
            "embabel.agent.decision.typesafe.model=secret-model",
            "embabel.agent.decision.prompted.llm-bean-name=secret-service"
    })
    void rejectsLegacySingularKeysWithoutRenderingValues(String legacyProperty) {
        runner.withPropertyValues("embabel.agent.decision.enabled=true", legacyProperty)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: " + legacyProperty.substring(0, legacyProperty.indexOf('=')))
                        .hasMessageNotContaining("secret-model")
                        .hasMessageNotContaining("secret-service"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "stub", "unknown", "TypeSafe"})
    void rejectsEveryUnsupportedConfiguredProvider(String provider) {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.selected.provider=" + provider)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.models.selected.provider"));
    }

    @Test
    void rejectsBeanDefinitionAndExistingSingletonCollisions() {
        runner.withBean("selected", DecisionModel.class, NoDecisionModel::create)
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.selected.provider=none")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.models.selected"));

        runner.withInitializer(context -> context.getBeanFactory().registerSingleton("selected", new Object()))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.selected.provider=none")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.models.selected"));
    }

    @Test
    void rejectsAliasAndParentContextCollisions() {
        runner.withBean("existing", DecisionModel.class, NoDecisionModel::create)
                .withInitializer(context -> context.getBeanFactory().registerAlias("existing", "selected"))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.selected.provider=none")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.models.selected"));

        try (GenericApplicationContext parent = new GenericApplicationContext()) {
            parent.registerBean("selected", DecisionModel.class, NoDecisionModel::create);
            parent.refresh();
            runner.withParent(parent)
                    .withPropertyValues(
                            "embabel.agent.decision.enabled=true",
                            "embabel.agent.decision.models.selected.provider=none")
                    .run(context -> assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.models.selected"));
        }
    }

    @Test
    void failedSingletonRegistrationRollsBackRegisteredBeansAndClosesEveryFacade() {
        LlmService<?> promptedService = service(
                "backend-name",
                "{\"answers\":[{\"keyId\":\"q\",\"kind\":\"YES_NO\",\"pTrue\":0.75}]}");
        class FailingBeanFactory extends DefaultListableBeanFactory {
            private final List<DecisionModel> registered = new ArrayList<>();

            @Override
            public void registerSingleton(String name, Object singleton) {
                if (singleton instanceof DecisionModel model) {
                    registered.add(model);
                    if (registered.size() == 2) throw new IllegalStateException("registration failed");
                }
                super.registerSingleton(name, singleton);
            }
        }
        FailingBeanFactory beanFactory = new FailingBeanFactory();
        beanFactory.registerSingleton("dynamicLlm", promptedService);

        var configured = new LinkedHashMap<String, DecisionProperties.Model>();
        configured.put("first", promptedModel("dynamicLlm"));
        configured.put("second", promptedModel("dynamicLlm"));
        var properties = new DecisionProperties(true, Duration.ofSeconds(30), "metadata", 65536,
                Set.of(), null, configured);

        assertThatThrownBy(() -> new AgentDecisionAutoConfiguration().decisionModelInitialization(
                        properties, new StandardEnvironment(), beanFactory, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registration failed");
        assertThat(beanFactory.containsSingleton("first")).isFalse();
        assertThat(beanFactory.registered).hasSize(2);
        assertThat(beanFactory.registered)
                .allSatisfy(model -> assertThat(model.ask(request())).isInstanceOf(DecisionOutcome.Failure.class));
    }

    @Test
    void rejectsInvalidModelNamesAndUndocumentedNestedKeys() {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.bad name.provider=none")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.models"));

        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.rules.provider=typesafe",
                        "embabel.agent.decision.models.rules.typesafe.api-key=SECRET")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "Invalid configuration: embabel.agent.decision.models.rules.typesafe.api-key")
                        .hasMessageNotContaining("SECRET"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Selected", "selected_model", "selected.model", "-selected", "selected-", "selected--model"})
    void rejectsNoncanonicalModelNames(String name) {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models." + name + ".provider=none")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.models"));
    }

    @Test
    void rejectsDurationsThatOverflowNanoseconds() {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.default-timeout=P106752D")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.default-timeout"));
    }

    @Test
    void promptedProviderResolvesExactDynamicServiceAndOptionsBeans() {
        LlmService<?> selected = service(
                "backend-name",
                "{\"answers\":[{\"keyId\":\"q\",\"kind\":\"YES_NO\",\"pTrue\":0.75}]}");
        LlmService<?> other = service("other", "{}");
        LlmOptions options = LlmOptions.withDefaults().withTemperature(0.17);

        runner.withUserConfiguration(DynamicLlmConfiguration.class)
                .withBean("dynamicSource", LlmService.class, () -> selected)
                .withBean("decisionOptions", LlmOptions.class, () -> options)
                .withBean("other", LlmService.class, () -> other)
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.review.provider=prompted",
                        "embabel.agent.decision.models.review.prompted.llm-bean-name=dynamicLlm",
                        "embabel.agent.decision.models.review.prompted.options-bean-name=decisionOptions")
                .run(context -> {
                    options.setTemperature(0.99);
                    assertThat(context.getBean("review", DecisionModel.class).ask(request()))
                            .isInstanceOf(DecisionOutcome.Success.class);
                    var captured = org.mockito.ArgumentCaptor.forClass(LlmOptions.class);
                    verify(selected, atLeastOnce()).createMessageSender(captured.capture());
                    assertThat(captured.getValue().getTemperature()).isEqualTo(0.17);
                    verify(other, never()).createMessageSender(any(LlmOptions.class));
                });
    }

    @Test
    void selectedTypeSafeSettingsRemainLazyAndEnforceSafeOrigins() {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.rules.provider=typesafe",
                        "embabel.agent.decision.models.rules.typesafe.model=jev-latest",
                        "embabel.agent.decision.models.rules.typesafe.base-url=https://user:SECRET@example.org")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "Invalid configuration: embabel.agent.decision.models.rules.typesafe.base-url")
                        .hasMessageNotContaining("SECRET"));

        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.rules.provider=typesafe",
                        "embabel.agent.decision.models.rules.typesafe.model=jev-latest",
                        "embabel.agent.decision.models.rules.typesafe.base-url=http://127.0.0.1:1",
                        "TYPESAFE_API_KEY=")
                .run(context -> {
                    DecisionOutcome.Failure outcome = (DecisionOutcome.Failure) context
                            .getBean("rules", DecisionModel.class).ask(request());
                    assertThat(outcome.getFailure()).isEqualTo(CallFailure.Unavailable);
                });
    }

    @Test
    void propertiesExposeAnImmutableNamedModelMapWithGlobalDefaults() {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.default-timeout=17s",
                        "embabel.agent.decision.models.rules.provider=typesafe",
                        "embabel.agent.decision.models.rules.typesafe.model=jev-latest",
                        "embabel.agent.decision.models.disabled.provider=none")
                .run(context -> {
                    DecisionProperties properties = context.getBean(DecisionProperties.class);
                    assertThat(properties.defaultTimeout()).hasSeconds(17);
                    assertThat(properties.models()).containsOnlyKeys("rules", "disabled");
                    assertThat(properties.models().get("rules").provider()).isEqualTo("typesafe");
                    assertThat(properties.models().get("rules").typesafe().model()).isEqualTo("jev-latest");
                    assertThat(properties.models().get("disabled").provider()).isEqualTo("none");
                    assertThatThrownBy(() -> properties.models().clear())
                            .isInstanceOf(UnsupportedOperationException.class);
                });
    }

    @Test
    void contextShutdownClosesConfiguredFacades() {
        LlmService<?> promptedService = service(
                "backend-name",
                "{\"answers\":[{\"keyId\":\"q\",\"kind\":\"YES_NO\",\"pTrue\":0.75}]}");
        AtomicReference<DecisionModel> configured = new AtomicReference<>();

        runner.withUserConfiguration(DynamicLlmConfiguration.class)
                .withBean("dynamicSource", LlmService.class, () -> promptedService)
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.prompted-review.provider=prompted",
                        "embabel.agent.decision.models.prompted-review.prompted.llm-bean-name=dynamicLlm")
                .run(context -> {
                    configured.set(context.getBean("prompted-review", DecisionModel.class));
                    assertThat(configured.get().ask(request())).isInstanceOf(DecisionOutcome.Success.class);
                });

        assertThat(configured.get().ask(request())).isInstanceOf(DecisionOutcome.Failure.class);
    }

    @Test
    void implicitJevAndCustomBeanRequireAnExplicitDefault() {
        integratedRunner(service("backend-name", "{}"))
                .withBean("custom", DecisionModel.class, () -> NoDecisionModel.create().named("custom", "user"))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.models.default-llm=backend-name")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Multiple decision models are registered; set "
                                + "'embabel.models.default-decision-model' to one of: [custom, jev]"));
    }

    @Test
    void unresolvedDecisionDefaultsAndRolesFailAtSharedProviderStartup() {
        integratedRunner(service("backend-name", "{}"))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.available.provider=none",
                        "embabel.models.default-llm=backend-name",
                        "embabel.models.default-decision-model=missing")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Default decision model 'missing' not found in available models: [available]"));

        integratedRunner(service("backend-name", "{}"))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.available.provider=none",
                        "embabel.models.default-llm=backend-name",
                        "embabel.models.decisions.review=missing")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "Decision model 'missing' for role review is not available: Choices are [available]"));

        integratedRunner(service("backend-name", "{}"))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.models.first.provider=none",
                        "embabel.agent.decision.models.second.provider=none",
                        "embabel.models.default-llm=backend-name")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Multiple decision models are registered; set "
                                + "'embabel.models.default-decision-model' to one of: [first, second]"));
    }

    @Test
    void configurationOwnsImmutableSnapshotsOfCallerCollections() {
        var allowlist = new LinkedHashSet<>(Set.of("answerIds"));
        var models = new LinkedHashMap<String, DecisionProperties.Model>();
        models.put("review", promptedModel("reviewLlm"));
        var properties = new DecisionProperties(true, Duration.ofSeconds(30), "metadata", 65536,
                allowlist, null, models);
        allowlist.clear();
        models.clear();
        assertThat(properties.recordAllowlist()).containsExactly("answerIds");
        assertThat(properties.models()).containsOnlyKeys("review");
        assertThatThrownBy(() -> properties.recordAllowlist().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> properties.models().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ApplicationContextRunner integratedRunner(LlmService<?> service) {
        return runner.withUserConfiguration(DynamicLlmConfiguration.class, SharedModelProviderConfiguration.class)
                .withBean("dynamicSource", LlmService.class, () -> service)
                .withBean("decisionOptions", LlmOptions.class, LlmOptions::withDefaults);
    }

    private static DecisionRequest request() {
        DecisionRequest.Builder builder = DecisionRequest.builder();
        builder.yesNo("q", "Proceed?");
        return builder.build();
    }

    private static DecisionProperties.Model promptedModel(String serviceName) {
        return new DecisionProperties.Model("prompted", null,
                new DecisionProperties.Prompted(serviceName, null));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static LlmService<?> service(String name, String responseText) {
        LlmService service = mock(LlmService.class);
        LlmMessageSender sender = mock(LlmMessageSender.class);
        when(service.getName()).thenReturn(name);
        when(service.getProvider()).thenReturn("test");
        when(service.createMessageSender(any(LlmOptions.class))).thenReturn(sender);
        when(sender.call(any(), any())).thenReturn(new LlmMessageResponse(mock(Message.class), responseText, null));
        return service;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DynamicLlmConfiguration {
        @Bean
        ProviderInitialization testProviderInitialization(ConfigurableListableBeanFactory beanFactory) {
            LlmService<?> selected = beanFactory.getBean("dynamicSource", LlmService.class);
            beanFactory.registerSingleton("dynamicLlm", selected);
            return new ProviderInitialization("test", List.of(), List.of(), Instant.now());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConfigurableModelProviderProperties.class)
    static class SharedModelProviderConfiguration {
        @Bean("modelProvider")
        ModelProvider modelProvider(
                ApplicationContext applicationContext,
                ConfigurableModelProviderProperties properties,
                List<ProviderInitialization> providerInitializations,
                List<DecisionModelInitialization> decisionInitializations) {
            return new AgentPlatformConfiguration().modelProvider(
                    applicationContext, properties, providerInitializations, decisionInitializations);
        }
    }
}
