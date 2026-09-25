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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.agent.config.models.typesafe.TypeSafeProperties;
import com.embabel.agent.typesafe.TypeSafeModelFactory;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.ClassificationService;
import com.embabel.common.ai.model.DecisionService;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

class AgentTypeSafeAutoConfigurationTest {
    private static final String PREFIX = TypeSafeProperties.PREFIX + ".";
    private static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";
    private static final PropositionRequest REQUEST =
            new PropositionRequest("The payment failed", "The payment needs attention");
    private static final String RESPONSE =
            """
            {"model":"jev-2026-09", "answers":{"proposition":{"type":"noul","noul":0.8}}}
            """;

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentTypeSafeAutoConfiguration.class))
                    .withPropertyValues("TYPESAFE_API_KEY=");

    @Test
    void requiresCredential() {
        runner.run(
                context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("TypeSafe API key is required");
                });
    }

    @Test
    void defaultsRegisterOneDecisionServiceThatAlsoClassifies() {
        configured()
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(TypeSafeModelFactory.class);
                            assertThat(context).hasSingleBean(DecisionService.class);
                            assertThat(context).hasSingleBean(ClassificationService.class);
                            assertThat(context.getBean(DecisionService.class))
                                    .isSameAs(context.getBean(ClassificationService.class));
                            assertThat(context.getBean(DecisionService.class).getName())
                                    .isEqualTo(TypeSafeModelFactory.DEFAULT_MODEL);
                            assertThat(context.getBean(DecisionService.class).getProvider())
                                    .isEqualTo(TypeSafeModelFactory.PROVIDER);
                        });
    }

    @Test
    void configurationExtendsFactoryAndBuildsTheConfiguredModel() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAssessment(server, "test-key", "custom-model");

        configured()
                .withPropertyValues(PREFIX + "model=custom-model")
                .withBean(RestClient.Builder.class, () -> builder)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(TypeSafeModelFactory.class))
                                    .isInstanceOf(
                                            com.embabel.agent.config.models.typesafe
                                                    .TypeSafeModelsConfig.class);
                            assertAnswered(context.getBean(DecisionService.class).assess(REQUEST));
                        });
        server.verify();
    }

    @Test
    void namedApplicationServiceBacksOffWithoutBlockingOtherDecisionModels() {
        var replacement = mock(DecisionService.class);
        var another = mock(DecisionService.class);
        runner
                .withBean("typeSafeDecisionService", DecisionService.class, () -> replacement)
                .withBean("anotherDecisionService", DecisionService.class, () -> another)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean("typeSafeDecisionService"))
                                    .isSameAs(replacement);
                            assertThat(context.getBeansOfType(DecisionService.class)).hasSize(2);
                            assertThat(context).doesNotHaveBean(TypeSafeModelFactory.class);
                        });
    }

    @Test
    void environmentCredentialWinsAndIsResolvedForEveryRequest() {
        var credentials = new HashMap<String, Object>();
        credentials.put("TYPESAFE_API_KEY", "first-key");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAssessment(server, "first-key", TypeSafeModelFactory.DEFAULT_MODEL);
        expectAssessment(server, "second-key", TypeSafeModelFactory.DEFAULT_MODEL);
        expectAssessment(server, "property-key", TypeSafeModelFactory.DEFAULT_MODEL);

        runner.withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "rotating-credentials", credentials)))
                .withPropertyValues(PREFIX + "api-key=property-key")
                .withBean(RestClient.Builder.class, () -> builder)
                .run(
                        context -> {
                            var service = context.getBean(DecisionService.class);
                            assertAnswered(service.assess(REQUEST));
                            credentials.put("TYPESAFE_API_KEY", "second-key");
                            assertAnswered(service.assess(REQUEST));
                            credentials.put("TYPESAFE_API_KEY", "");
                            assertAnswered(service.assess(REQUEST));
                        });
        server.verify();
    }

    @Test
    void qualifiedPlatformBuilderWinsOverOtherBuilders() {
        var selected = RestClient.builder();
        var ignored = mock(RestClient.Builder.class);
        var server = MockRestServiceServer.bindTo(selected).build();
        expectAssessment(server, "test-key", TypeSafeModelFactory.DEFAULT_MODEL);

        configured()
                .withBean("aiModelRestClientBuilder", RestClient.Builder.class, () -> selected)
                .withBean("other", RestClient.Builder.class, () -> ignored)
                .run(
                        context ->
                                assertAnswered(
                                        context.getBean(DecisionService.class).assess(REQUEST)));
        server.verify();
        verifyNoInteractions(ignored);
    }

    @Test
    void ambiguousBuildersFallBackWithoutResolvingEitherBuilder() {
        var one = mock(RestClient.Builder.class);
        var two = mock(RestClient.Builder.class);
        configured()
                .withBean("one", RestClient.Builder.class, () -> one)
                .withBean("two", RestClient.Builder.class, () -> two)
                .run(context -> assertThat(context).hasSingleBean(DecisionService.class));
        verifyNoInteractions(one, two);
    }

    @Test
    void applicationRegistryObservesFrameworkProviderAndHttpLayers() {
        var registry = ObservationRegistry.create();
        var observations = new ArrayList<String>();
        registry.observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                observations.add(context.getName());
                            }
                        });
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAssessment(server, "test-key", TypeSafeModelFactory.DEFAULT_MODEL);

        configured()
                .withBean(ObservationRegistry.class, () -> registry)
                .withBean("aiModelRestClientBuilder", RestClient.Builder.class, () -> builder)
                .run(
                        context ->
                                assertAnswered(
                                        context.getBean(DecisionService.class).assess(REQUEST)));

        assertThat(observations)
                .contains(
                        "embabel.ai.decision",
                        "embabel.typesafe.request",
                        "http.client.requests");
        server.verify();
    }

    @Test
    void propertiesRedactCredentials() {
        var properties =
                new TypeSafeProperties(
                        "test-key",
                        "https://user:proxy-secret@example.test/gateway?token=query-secret",
                        "model\nforged-log-record",
                        1024);

        assertThat(properties.toString())
                .doesNotContain(
                        "test-key",
                        "proxy-secret",
                        "query-secret",
                        "gateway",
                        "forged-log-record");
    }

    @Test
    void malformedEndpointFailureDoesNotRetainConfigurationSecrets() {
        configured()
                .withPropertyValues(
                        PREFIX
                                + "base-url=https://user:uri-secret@example.test/%ZZ?token=query-secret")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage("TypeSafe base URL is invalid");
                            for (var failure = context.getStartupFailure();
                                    failure != null;
                                    failure = failure.getCause()) {
                                assertThat(failure.toString())
                                        .doesNotContain("uri-secret", "query-secret", "%ZZ");
                            }
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"model=", "max-response-bytes=0", "base-url=%%%"})
    void invalidOptionsAreRejected(String property) {
        configured()
                .withPropertyValues(PREFIX + property)
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues(PREFIX + "api-key=test-key");
    }

    /** Install one response expectation without copying credentials or model assertions. */
    private static void expectAssessment(
            MockRestServiceServer server, String credential, String model) {
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(header("Authorization", "Bearer " + credential))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(model)))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
    }

    private static void assertAnswered(PropositionResult result) {
        assertThat(result).isInstanceOf(PropositionResult.Answered.class);
    }
}
