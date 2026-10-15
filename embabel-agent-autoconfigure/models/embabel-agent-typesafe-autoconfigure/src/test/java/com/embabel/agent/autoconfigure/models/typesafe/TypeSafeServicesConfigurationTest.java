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
package com.embabel.agent.autoconfigure.models.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.embabel.agent.config.models.typesafe.TypeSafeProperties;
import com.embabel.agent.typesafe.TypeSafeModelFactory;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.stream.Collectors;

class TypeSafeServicesConfigurationTest {
    private static final String SERVICES = TypeSafeProperties.PREFIX + ".services.";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentTypeSafeAutoConfiguration.class))
                    .withPropertyValues(
                            "TYPESAFE_API_KEY=", TypeSafeProperties.PREFIX + ".api-key=test-key");

    private static Map<String, String> modelsByBean(Map<String, DecisionService> services) {
        return services.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getName()));
    }

    @Test
    void configuredServicesRegisterAsDecisionServicesReportingTheirModels() {
        runner.withPropertyValues(
                        SERVICES + "jev.model=jev-latest", SERVICES + "jev-fast.model=jev-fast-model")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var services = context.getBeansOfType(DecisionService.class);
                            assertThat(modelsByBean(services))
                                    .containsExactlyInAnyOrderEntriesOf(
                                            Map.of(
                                                    "typeSafeDecisionService",
                                                    TypeSafeModelFactory.DEFAULT_MODEL,
                                                    "jev",
                                                    "jev-latest",
                                                    "jev-fast",
                                                    "jev-fast-model"));
                            assertThat(services.values())
                                    .allSatisfy(
                                            s ->
                                                    assertThat(s.getProvider())
                                                            .isEqualTo(
                                                                    TypeSafeModelFactory.PROVIDER));
                        });
    }

    @Test
    void defaultCandidateNamesTheDefaultTypeSafeService() {
        runner.run(
                context ->
                        assertThat(context.getBean(DecisionServiceRegistry.DefaultCandidate.class))
                                .isEqualTo(
                                        new DecisionServiceRegistry.DefaultCandidate(
                                                "typeSafeDecisionService")));
    }

    @Test
    void applicationBeanWithAServiceNameReplacesOnlyThatService() {
        var replacement = mock(DecisionService.class);
        runner.withPropertyValues(
                        SERVICES + "jev.model=jev-latest", SERVICES + "jev-fast.model=jev-fast-model")
                .withBean("jev", DecisionService.class, () -> replacement)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean("jev")).isSameAs(replacement);
                            assertThat(context.getBean("jev-fast", DecisionService.class).getName())
                                    .isEqualTo("jev-fast-model");
                            assertThat(context.getBeansOfType(DecisionService.class)).hasSize(3);
                        });
    }

    @Test
    void applicationClassificationServiceWithAServiceNameReplacesThatService() {
        var replacement = mock(ClassificationService.class);
        runner.withPropertyValues(SERVICES + "jev.model=jev-latest")
                .withBean("jev", ClassificationService.class, () -> replacement)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean("jev")).isSameAs(replacement);
                        });
    }

    @Test
    void keyAlsoConfiguredAsAPromptedServiceFailsStartupNamingBothProperties() {
        var prompted = "embabel.agent.platform.decisions.llm.services.jev";
        runner.withPropertyValues(SERVICES + "jev.model=jev-latest")
                .withInitializer(
                        context -> {
                            var definition =
                                    BeanDefinitionBuilder.genericBeanDefinition(
                                                    DecisionService.class,
                                                    () -> mock(DecisionService.class))
                                            .getBeanDefinition();
                            definition.setAttribute(
                                    "com.embabel.decision.configuredServiceProperty", prompted);
                            ((BeanDefinitionRegistry) context)
                                    .registerBeanDefinition("jev", definition);
                        })
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(failureMessages(context.getStartupFailure()))
                                    .contains(SERVICES + "jev and " + prompted)
                                    .contains("Rename one of the keys");
                        });
    }

    @Test
    void keyNamingABeanThatIsNotAServiceFailsStartupNamingTheBean() {
        runner.withPropertyValues(SERVICES + "jev.model=jev-latest")
                .withBean("jev", StringBuilder.class, StringBuilder::new)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(failureMessages(context.getStartupFailure()))
                                    .contains(SERVICES + "jev is configured")
                                    .contains("'jev' of type java.lang.StringBuilder")
                                    .contains("Rename the key under " + TypeSafeProperties.PREFIX);
                        });
    }

    @Test
    void blankModelFailsStartupNamingTheProperty() {
        runner.withPropertyValues(SERVICES + "jev.model= ")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(failureMessages(context.getStartupFailure()))
                                    .contains(SERVICES + "jev.model must name a TypeSafe model");
                        });
    }

    @Test
    void unknownServiceKeyFailsStartupNamingTheProperty() {
        runner.withPropertyValues(SERVICES + "jev.modle=jev-latest")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(failureMessages(context.getStartupFailure()))
                                    .contains(SERVICES + "jev.modle");
                        });
    }

    @Test
    void applicationDefaultServiceReplacesOnlyTheDefaultAndNamedServicesStillRegister() {
        var replacement = mock(DecisionService.class);
        runner.withPropertyValues(SERVICES + "jev.model=jev-latest")
                .withBean("typeSafeDecisionService", DecisionService.class, () -> replacement)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean("typeSafeDecisionService"))
                                    .isSameAs(replacement);
                            assertThat(context)
                                    .doesNotHaveBean(DecisionServiceRegistry.DefaultCandidate.class);
                            assertThat(context.getBean("jev", DecisionService.class).getName())
                                    .isEqualTo("jev-latest");
                            assertThat(context.getBeansOfType(DecisionService.class)).hasSize(2);
                        });
    }

    @Test
    void applicationDefaultServiceWithoutNamedServicesNeedsNoCredential() {
        var replacement = mock(DecisionService.class);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentTypeSafeAutoConfiguration.class))
                .withPropertyValues("TYPESAFE_API_KEY=")
                .withBean("typeSafeDecisionService", DecisionService.class, () -> replacement)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(TypeSafeModelFactory.class);
                            assertThat(context.getBeansOfType(DecisionService.class)).hasSize(1);
                        });
    }

    @Test
    void existingPropertiesStillBindWithServiceAndFamilyKeysPresent() {
        runner.withPropertyValues(
                        TypeSafeProperties.PREFIX + ".model=custom-model",
                        TypeSafeProperties.PREFIX + ".max-response-bytes=2048",
                        SERVICES + "jev.model=jev-latest",
                        "embabel.models.decision.default=jev",
                        "embabel.models.decision.roles.support-triage=jev")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var properties = context.getBean(TypeSafeProperties.class);
                            assertThat(properties.model()).isEqualTo("custom-model");
                            assertThat(properties.maxResponseBytes()).isEqualTo(2048);
                            assertThat(
                                            context.getBean(
                                                            "typeSafeDecisionService",
                                                            DecisionService.class)
                                                    .getName())
                                    .isEqualTo("custom-model");
                            assertThat(context.getBean("jev", DecisionService.class).getName())
                                    .isEqualTo("jev-latest");
                        });
    }

    private static String failureMessages(Throwable failure) {
        var messages = new StringBuilder();
        for (var t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
