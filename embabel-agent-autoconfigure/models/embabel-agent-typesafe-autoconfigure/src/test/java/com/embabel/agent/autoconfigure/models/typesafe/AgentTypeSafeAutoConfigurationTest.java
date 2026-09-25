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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.agent.config.models.typesafe.TypeSafeProperties;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Noul;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

class AgentTypeSafeAutoConfigurationTest {
    private static final String PREFIX = TypeSafeProperties.PREFIX + ".";
    private static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentTypeSafeAutoConfiguration.class));

    @Test
    void disabledByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(TypeSafeClient.class));
    }

    @Test
    void enabledRequiresCredential() {
        runner.withPropertyValues(PREFIX + "enabled=true", "TYPESAFE_API_KEY=")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "TypeSafe API key is required when enabled");
                        });
    }

    @Test
    void environmentCredentialAndDefaults() {
        runner.withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "test-environment",
                                                        Map.of(
                                                                "TYPESAFE_API_KEY",
                                                                "environment-secret"))))
                .withPropertyValues(PREFIX + "enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(TypeSafeClient.class);
                            var properties = context.getBean(TypeSafeProperties.class);
                            assertThat(properties.model()).isEqualTo("jev-latest");
                            assertThat(properties.baseUri()).hasToString("https://api.typesafe.ai");
                            assertThat(properties.connectTimeout())
                                    .isEqualTo(Duration.ofSeconds(10));
                            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
                            assertThat(properties.maxResponseBytes()).isEqualTo(1024 * 1024);
                        });
    }

    @Test
    void customClientBacksOffEvenWithoutCredentialOrValidOptions() {
        var supplied = mock(TypeSafeClient.class);
        runner.withBean(TypeSafeClient.class, () -> supplied)
                .withPropertyValues(
                        PREFIX + "enabled=true", PREFIX + "base-uri=%%%", "TYPESAFE_API_KEY=")
                .run(
                        context ->
                                assertThat(context.getBean(TypeSafeClient.class))
                                        .isSameAs(supplied));
    }

    @Test
    void twoClientsBackOff() {
        runner.withBean("one", TypeSafeClient.class, () -> mock(TypeSafeClient.class))
                .withBean("two", TypeSafeClient.class, () -> mock(TypeSafeClient.class))
                .withPropertyValues(PREFIX + "enabled=true", "TYPESAFE_API_KEY=")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(TypeSafeClient.class)).hasSize(2);
                        });
    }

    @Test
    void enabledInvalidOptionsFailAndSecretsAreRedacted() {
        runner.withPropertyValues(
                        PREFIX + "enabled=true",
                        PREFIX + "api-key=secret-sentinel",
                        PREFIX + "read-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "api-key=secret-sentinel")
                .run(
                        context ->
                                assertThat(context.getBean(TypeSafeProperties.class).toString())
                                        .doesNotContain("secret-sentinel"));
    }

    @Test
    void configuredCredentialWinsAndUniqueBootBuilderRetainsFactoryAndInterceptor() {
        var builder =
                RestClient.builder()
                        .requestInterceptor(
                                (request, body, execution) -> {
                                    request.getHeaders().add("X-Application", "retained");
                                    return execution.execute(request, body);
                                });
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(header("Authorization", "Bearer configured-secret"))
                .andExpect(header("X-Application", "retained"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jev-latest")))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        enabled()
                .withPropertyValues(
                        PREFIX + "api-key=configured-secret", "TYPESAFE_API_KEY=environment-secret")
                .withBean(RestClient.Builder.class, () -> builder)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(TypeSafeClient.class)
                                                    .systemOne(
                                                            "state", Map.of("ok", Noul.of("ok?")))
                                                    .noulValue("ok"))
                                    .isEqualTo(0.8);
                        });
        server.verify();
    }

    @Test
    void qualifiedPlatformBuilderWinsOverOtherBuilders() {
        var selected = RestClient.builder();
        var ignored = mock(RestClient.Builder.class);
        var server = MockRestServiceServer.bindTo(selected).build();
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        enabled()
                .withBean("aiModelRestClientBuilder", RestClient.Builder.class, () -> selected)
                .withBean("other", RestClient.Builder.class, () -> ignored)
                .run(
                        context ->
                                context.getBean(TypeSafeClient.class)
                                        .systemOne("state", Map.of("ok", Noul.of("ok?"))));
        server.verify();
        verifyNoInteractions(ignored);
    }

    @Test
    void ambiguousBuildersAreNotChosen() {
        var one = mock(RestClient.Builder.class);
        var two = mock(RestClient.Builder.class);
        enabled()
                .withBean("one", RestClient.Builder.class, () -> one)
                .withBean("two", RestClient.Builder.class, () -> two)
                .run(context -> assertThat(context).hasSingleBean(TypeSafeClient.class));
        verifyNoInteractions(one, two);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aiModelRestClientBuilder", "restClientBuilder"})
    void applicationObservationRegistryIsUsedWithoutMutatingSharedBuilder(String builderName) {
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
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://application.example/unrelated"))
                .andRespond(withSuccess("unrelated", MediaType.TEXT_PLAIN));
        enabled()
                .withBean(ObservationRegistry.class, () -> registry)
                .withBean(builderName, RestClient.Builder.class, () -> builder)
                .run(
                        context ->
                                context.getBean(TypeSafeClient.class)
                                        .systemOne("state", Map.of("ok", Noul.of("ok?"))));
        assertThat(observations).contains("embabel.typesafe.request", "http.client.requests");
        int count = observations.size();
        assertThat(
                        builder.build()
                                .get()
                                .uri("https://application.example/unrelated")
                                .retrieve()
                                .body(String.class))
                .isEqualTo("unrelated");
        assertThat(observations).hasSize(count);
        server.verify();
    }

    @Test
    void existingJsonConverterCannotBypassStrictProtocol() {
        for (var body :
                new String[] {
                    """
                    {"answers": {"ok": {"type": "noul", "noul": 0.8, "noul": 0.9}}}
                    """,
                    RESPONSE + " {}"
                }) {
            var builder =
                    RestClient.builder()
                            .configureMessageConverters(
                                    converters ->
                                            converters
                                                    .registerDefaults()
                                                    .withJsonConverter(
                                                            new JacksonJsonHttpMessageConverter()));
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(SYSTEM_ONE_URI))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            enabled()
                    .withBean(RestClient.Builder.class, () -> builder)
                    .run(
                            context -> {
                                var client = context.getBean(TypeSafeClient.class);
                                var questions = Map.of("ok", Noul.of("ok?"));
                                assertThatThrownBy(() -> client.systemOne("state", questions))
                                        .isInstanceOf(RuntimeException.class);
                            });
            server.verify();
        }
    }

    @Test
    void disabledNeverResolvesHttpBuilder() {
        runner.withBean(
                        RestClient.Builder.class,
                        () -> {
                            throw new AssertionError("Builder must stay lazy");
                        },
                        definition -> definition.setLazyInit(true))
                .withPropertyValues(
                        PREFIX + "enabled=false", PREFIX + "api-key=", PREFIX + "model=")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(TypeSafeClient.class);
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "model=",
                "connect-timeout=0s",
                "read-timeout=-1s",
                "max-response-bytes=0",
                "base-uri=file:///tmp/typesafe"
            })
    void invalidOptionsAreRejectedWhenEnabled(String property) {
        enabled()
                .withPropertyValues(PREFIX + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void upstreamStarterBacksOffWhenBothAutoConfigurationsArePresent() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        enabled()
                .withConfiguration(
                        AutoConfigurations.of(
                                org.springaicommunity.typesafe.autoconfigure
                                        .TypeSafeAutoConfiguration.class))
                .withPropertyValues("spring.ai.typesafe.api-key=upstream-key")
                .withBean(RestClient.Builder.class, () -> builder)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(TypeSafeClient.class);
                            assertThat(context).hasBean("typeSafeClient");
                            context.getBean(TypeSafeClient.class)
                                    .systemOne("state", Map.of("ok", Noul.of("ok?")));
                        });
        server.verify();
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "api-key=test-key");
    }

    private static final String RESPONSE =
            """
            {"answers": {"ok": {"type": "noul", "noul": 0.8}}}
            """;
}
