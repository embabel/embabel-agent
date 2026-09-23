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
import com.embabel.agent.decision.DecisionOutcome;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.DecisionRecordPolicy;
import com.embabel.agent.decision.NoDecisionModel;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.loop.LlmMessageResponse;
import com.embabel.agent.spi.loop.LlmMessageSender;
import com.embabel.chat.Message;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
        runner.run(context -> assertThat(context).doesNotHaveBean(DecisionModel.class));
        runner.withPropertyValues("embabel.agent.decision.enabled=false", "embabel.agent.decision.provider=stub")
                .run(context -> assertThat(context).doesNotHaveBean(DecisionModel.class));
    }

    @Test
    void noneCreatesOneFinalFacadeAndReturnsDisabled() {
        runner.withPropertyValues("embabel.agent.decision.enabled=true", "embabel.agent.decision.provider=none")
                .run(context -> {
                    assertThat(context).hasSingleBean(DecisionModel.class).hasBean("decisionModel");
                    DecisionRequest.Builder builder = DecisionRequest.builder();
                    builder.yesNo("q", "Proceed?");
                    DecisionOutcome.Failure outcome = (DecisionOutcome.Failure) context.getBean(DecisionModel.class)
                            .ask(builder.build());
                    assertThat(outcome.getFailure()).isEqualTo(CallFailure.Disabled);
                });
    }

    @Test
    void invalidSelectorUsesOnlyThePropertyKey() {
        runner.withPropertyValues("embabel.agent.decision.enabled=true", "embabel.agent.decision.provider=SECRET_VALUE")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.provider")
                        .hasMessageNotContaining("SECRET_VALUE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "stub", "unknown", "TypeSafe"})
    void rejectsEveryUnsupportedProviderSelector(String provider) {
        runner.withPropertyValues("embabel.agent.decision.enabled=true", "embabel.agent.decision.provider=" + provider)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.provider"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCommonConfiguration")
    void commonValidationNamesOnlyTheRejectedProperty(String property, String key) {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=none",
                        "embabel.agent.decision." + property)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision." + key));
    }

    static Stream<Arguments> invalidCommonConfiguration() {
        return Stream.of(
                Arguments.of("default-timeout=0s", "default-timeout"),
                Arguments.of("default-timeout=-1s", "default-timeout"),
                Arguments.of("default-timeout=not-a-duration", "default-timeout"),
                Arguments.of("record-mode=verbose", "record-mode"),
                Arguments.of("full-record-max-bytes=0", "full-record-max-bytes"),
                Arguments.of("full-record-max-bytes=1048577", "full-record-max-bytes"),
                Arguments.of("full-record-max-bytes=not-an-int", "full-record-max-bytes"),
                Arguments.of("record-allowlist=*", "record-allowlist"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTypeSafeConfiguration")
    void validatesOnlyTheSelectedTypeSafeProperties(String property, String key) {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=typesafe",
                        "embabel.agent.decision.typesafe.model=synthetic",
                        "embabel.agent.decision." + property)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision." + key));
    }

    static Stream<Arguments> invalidTypeSafeConfiguration() {
        return Stream.of(
                Arguments.of("typesafe.model=", "typesafe.model"),
                Arguments.of("typesafe.connect-timeout=0s", "typesafe.connect-timeout"),
                Arguments.of("typesafe.connect-timeout=bad", "typesafe.connect-timeout"),
                Arguments.of("typesafe.base-url=http://localhost:1234", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=http://localhost.example:1234", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=http://127.0.0.1.example:1234", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=http://[::2]:1234", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=http://[::1]:1234/v1", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=http://[[::1]]:1234", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=http://[::1", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=https://example.org/v1", "typesafe.base-url"),
                Arguments.of("typesafe.base-url=https://user:secret@example.org", "typesafe.base-url"));
    }

    @Test
    void acceptsTheTypeSafeFactoryIpv6LoopbackSpelling() {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=typesafe",
                        "embabel.agent.decision.typesafe.model=synthetic",
                        "embabel.agent.decision.typesafe.base-url=http://[::1]:1234")
                .run(context -> assertThat(context).hasSingleBean(DecisionModel.class));
    }

    @Test
    void userModelWinsBeforeInvalidConfigurationIsRead() {
        DecisionModel user = NoDecisionModel.create();
        runner.withBean("consumerDecision", DecisionModel.class, () -> user)
                .withPropertyValues("embabel.agent.decision.enabled=true", "embabel.agent.decision.provider=SECRET_VALUE")
                .run(context -> {
                    assertThat(context).hasSingleBean(DecisionModel.class);
                    assertThat(context.getBean(DecisionModel.class)).isSameAs(user);
                });
    }

    @Test
    void onlySelectedProviderSettingsAreValidated() {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=none",
                        "embabel.agent.decision.typesafe.base-url=not a uri",
                        "embabel.agent.decision.prompted.llm-bean-name=")
                .run(context -> assertThat(context).hasSingleBean(DecisionModel.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1048576"})
    void fullRecordByteBoundariesAreInclusive(String bytes) {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=none",
                        "embabel.agent.decision.record-mode=full",
                        "embabel.agent.decision.record-allowlist=answerIds",
                        "embabel.agent.decision.full-record-max-bytes=" + bytes)
                .run(context -> assertThat(context).hasSingleBean(DecisionModel.class));
    }

    @Test
    void promptedMissingOrWrongNamedBeansFailWithSafeKeys() {
        runner.withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=prompted",
                        "embabel.agent.decision.prompted.llm-bean-name=missing")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.prompted.llm-bean-name"));

        runner.withBean("wrongOptions", String.class, () -> "secret-option")
                .withBean("service", LlmService.class, () -> service("selected", "{}"))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=prompted",
                        "embabel.agent.decision.prompted.llm-bean-name=service",
                        "embabel.agent.decision.prompted.options-bean-name=wrongOptions")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Invalid configuration: embabel.agent.decision.prompted.options-bean-name"));
    }

    @Test
    void missingCredentialIsLazyAndNeverTouchesTheNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            runner.withPropertyValues(
                            "embabel.agent.decision.enabled=true",
                            "embabel.agent.decision.provider=typesafe",
                            "embabel.agent.decision.typesafe.model=synthetic-model",
                            "embabel.agent.decision.typesafe.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "embabel.agent.decision.typesafe.api-key=",
                            "TYPESAFE_API_KEY=")
                    .run(context -> {
                        assertThat(requests).hasValue(0);
                        DecisionOutcome.Failure result = (DecisionOutcome.Failure) context.getBean(DecisionModel.class)
                                .ask(request());
                        assertThat(result.getFailure()).isEqualTo(CallFailure.Unavailable);
                        assertThat(requests).hasValue(0);
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitCredentialWinsAndEnvironmentFallbackIsUsedWhenExplicitIsBlank() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(422, -1);
            exchange.close();
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            ApplicationContextRunner configured = runner.withPropertyValues(
                    "embabel.agent.decision.enabled=true",
                    "embabel.agent.decision.provider=typesafe",
                    "embabel.agent.decision.typesafe.model=synthetic-model",
                    "embabel.agent.decision.typesafe.base-url=" + origin);
            configured.withPropertyValues(
                            "embabel.agent.decision.typesafe.api-key=explicit-key",
                            "TYPESAFE_API_KEY=fallback-key")
                    .run(context -> {
                        context.getBean(DecisionModel.class).ask(request());
                        assertThat(authorization).hasValue("Bearer explicit-key");
                    });
            authorization.set(null);
            configured.withPropertyValues(
                            "embabel.agent.decision.typesafe.api-key=",
                            "TYPESAFE_API_KEY=fallback-key")
                    .run(context -> {
                        context.getBean(DecisionModel.class).ask(request());
                        assertThat(authorization).hasValue("Bearer fallback-key");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void promptedProviderResolvesTheConfiguredServiceAndOptionsByExactName() {
        LlmService selected = service("selected", "{\"answers\":[{\"keyId\":\"q\",\"kind\":\"YES_NO\",\"pTrue\":0.75}]}");
        LlmService other = service("other", "{}");
        LlmOptions selectedOptions = LlmOptions.withDefaults().withTemperature(0.17);
        LlmOptions otherOptions = LlmOptions.withDefaults().withTemperature(0.91);
        EmbabelObjectMapperHolder selectedMapper = EmbabelObjectMapperHolder.createDefault();
        EmbabelObjectMapperHolder otherMapper = EmbabelObjectMapperHolder.createDefault();

        runner.withBean("selectedService", LlmService.class, () -> selected)
                .withBean("otherService", LlmService.class, () -> other)
                .withBean("selectedOptions", LlmOptions.class, () -> selectedOptions)
                .withBean("otherOptions", LlmOptions.class, () -> otherOptions)
                .withBean("selectedMapper", EmbabelObjectMapperHolder.class, () -> selectedMapper)
                .withBean("otherMapper", EmbabelObjectMapperHolder.class, () -> otherMapper)
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=prompted",
                        "embabel.agent.decision.prompted.llm-bean-name=selectedService",
                        "embabel.agent.decision.prompted.options-bean-name=selectedOptions",
                        "embabel.agent.decision.mapper-bean-name=selectedMapper")
                .run(context -> {
                    selectedOptions.setTemperature(0.99);
                    assertThat(context.getBean(DecisionModel.class).ask(request())).isInstanceOf(DecisionOutcome.Success.class);
                    var options = org.mockito.ArgumentCaptor.forClass(LlmOptions.class);
                    verify(selected, atLeastOnce()).createMessageSender(options.capture());
                    assertThat(options.getValue().getTemperature()).isEqualTo(0.17);
                    verify(other, never()).createMessageSender(any(LlmOptions.class));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "metadata", "full"})
    void configuredRecordDefaultsAndExplicitOverridesRemainInsideTheFacade(String mode) {
        ApplicationContextRunner configured = runner.withPropertyValues(
                "embabel.agent.decision.enabled=true",
                "embabel.agent.decision.provider=none",
                "embabel.agent.decision.record-mode=" + mode);
        if (mode.equals("full")) configured = configured.withPropertyValues("embabel.agent.decision.record-allowlist=answerIds");
        configured.run(context -> {
            DecisionModel model = context.getBean(DecisionModel.class);
            DecisionOutcome.Failure inherited = (DecisionOutcome.Failure) model.ask(request());
            if (mode.equals("none")) assertThat(inherited.getRecord()).isNull();
            else assertThat(inherited.getRecord()).isNotNull();

            DecisionRequest.Builder explicit = DecisionRequest.builder().recordPolicy(DecisionRecordPolicy.metadata());
            explicit.yesNo("q", "Proceed?");
            assertThat(((DecisionOutcome.Failure) model.ask(explicit.build())).getRecord()).isNotNull();
        });
    }

    private static DecisionRequest request() {
        DecisionRequest.Builder builder = DecisionRequest.builder();
        builder.yesNo("q", "Proceed?");
        return builder.build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static LlmService service(String name, String responseText) {
        LlmService service = mock(LlmService.class);
        LlmMessageSender sender = mock(LlmMessageSender.class);
        when(service.getName()).thenReturn(name);
        when(service.getProvider()).thenReturn("test");
        when(service.createMessageSender(any(LlmOptions.class))).thenReturn(sender);
        when(sender.call(any(), any())).thenReturn(new LlmMessageResponse(mock(Message.class), responseText, null));
        return service;
    }
}
