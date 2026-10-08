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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.agent.config.models.typesafe.TypeSafeProperties;
import com.embabel.agent.typesafe.TypeSafeModelFactory;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.DecisionServiceRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.stream.Collectors;

class TypeSafeServicesConfigurationTest {
    private static final String SERVICES = TypeSafeProperties.PREFIX + ".services.";
    private static final PropositionRequest REQUEST =
            new PropositionRequest("The payment failed", "The payment needs attention");
    private static final String RESPONSE =
            """
            {"model":"jev-2026-09", "answers":{"proposition":{"type":"noul","noul":0.8}}}
            """;

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

    @Test
    void entryWithItsOwnEndpointBuildsItsOwnService() {
        runner.withPropertyValues(
                        SERVICES + "local.model=decider-2b",
                        SERVICES + "local.base-url=http://127.0.0.1:1",
                        SERVICES + "local.provider=decider")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var local = context.getBean("local", DecisionService.class);
                            assertThat(local.getProvider()).isEqualTo("decider");
                            assertThat(local.getName()).isEqualTo("decider-2b");
                            assertThat(
                                            context.getBean(
                                                            "typeSafeDecisionService",
                                                            DecisionService.class)
                                                    .getProvider())
                                    .isEqualTo(TypeSafeModelFactory.PROVIDER);
                        });
    }

    @Test
    void entryWithItsOwnEndpointNeverSendsTheSharedCredential() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:1/v1/systemone"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        runner.withPropertyValues(
                        SERVICES + "local.model=decider-2b",
                        SERVICES + "local.base-url=http://127.0.0.1:1")
                .withBean(RestClient.Builder.class, () -> builder)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertAnswered(
                                    context.getBean("local", DecisionService.class).assess(REQUEST));
                            assertAnswered(
                                    context.getBean("typeSafeDecisionService", DecisionService.class)
                                            .assess(REQUEST));
                        });
        server.verify();
    }

    @Test
    void entryWithoutOverridesSharesTheDefaultFactory() {
        runner.withPropertyValues(
                        TypeSafeProperties.PREFIX + ".provider=decider",
                        SERVICES + "jev.model=jev-latest")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean("jev", DecisionService.class).getProvider())
                                    .isEqualTo("decider");
                        });
        runner.withPropertyValues(SERVICES + "jev.model=jev-latest")
                .run(
                        context ->
                                assertThat(
                                                context.getBean("jev", DecisionService.class)
                                                        .getProvider())
                                        .isEqualTo(TypeSafeModelFactory.PROVIDER));
    }

    @Test
    void entryAtTheCloudEndpointWithoutItsOwnCredentialFails() {
        runner.withPropertyValues(
                        SERVICES + "cloud.model=jev-latest",
                        SERVICES + "cloud.base-url=https://api.typesafe.ai")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(failureMessages(context.getStartupFailure()))
                                    .contains(SERVICES + "cloud.api-key")
                                    .doesNotContain("test-key");
                        });
    }

    @Test
    void blankEntryValuesFailStartup() {
        runner.withPropertyValues(SERVICES + "local.model=decider-2b", SERVICES + "local.base-url= ")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(failureMessages(context.getStartupFailure()))
                                    .contains(SERVICES + "local.base-url must not be blank");
                        });
        runner.withPropertyValues(SERVICES + "local.model=decider-2b", SERVICES + "local.api-key= ")
                .run(
                        context ->
                                assertThat(failureMessages(context.getStartupFailure()))
                                        .contains(SERVICES + "local.api-key must not be blank"));
        runner.withPropertyValues(SERVICES + "local.model=decider-2b", SERVICES + "local.provider= ")
                .run(
                        context ->
                                assertThat(failureMessages(context.getStartupFailure()))
                                        .contains(SERVICES + "local.provider must not be blank"));
    }

    @Test
    void unknownEntryKeyFailsStartup() {
        runner.withPropertyValues(
                        SERVICES + "local.model=decider-2b", SERVICES + "local.base-url-typo=x")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(failureMessages(context.getStartupFailure()))
                                    .contains(SERVICES + "local.base-url-typo");
                        });
    }

    @Test
    void entryPropertiesRedactTheirCredentialAndEndpoint() {
        var service =
                new com.embabel.agent.config.models.typesafe.TypeSafeServicesRegistrar
                        .ServiceProperties();
        service.setModel("decider-2b");
        service.setBaseUrl("https://user:url-secret@example.test/gateway");
        service.setApiKey("entry-secret");
        service.setProvider("decider");

        assertThat(service.toString())
                .doesNotContain("entry-secret", "url-secret", "gateway")
                .contains("provider=decider");
    }

    private static void assertAnswered(PropositionResult result) {
        assertThat(result).isInstanceOf(PropositionResult.Answered.class);
    }

    private static String failureMessages(Throwable failure) {
        var messages = new StringBuilder();
        for (var t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
