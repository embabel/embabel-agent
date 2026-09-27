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
package com.embabel.agent.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.spi.DecisionContentCapture;
import com.embabel.common.ai.model.DecisionService;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@ResourceLock("DecisionContentCapture")
class TypeSafeDiagnosticsTest {
    private static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";
    private static final String SENTINEL_INPUT = "SENTINEL_INPUT customer text";
    private static final String SENTINEL_KEY = "SENTINEL_KEY_value";
    private static final String SENTINEL_INSTRUCTION = "SENTINEL_INSTRUCTION is this urgent?";
    private static final String SENTINEL_BODY = "SENTINEL_BODY provider detail";
    private static final List<String> SENTINELS =
            List.of("SENTINEL_INPUT", "SENTINEL_KEY", "SENTINEL_INSTRUCTION", "SENTINEL_BODY");

    private final DecisionSpec spec =
            DecisionSpec.of(
                    Questions.named("urgent").proposition(SENTINEL_INSTRUCTION).build(),
                    Questions.named("department")
                            .choice("Which team?")
                            .option("billing", "Payments")
                            .option("technical", "Bugs")
                            .build());

    @Test
    void unavailableProviderLogsOneWarnWithBoundedFields() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SENTINEL_BODY));

        var events = capture(Level.DEBUG, () -> fixture.service().ask(SENTINEL_INPUT, spec));

        fixture.server().verify();
        var warnings = serviceWarnings(events);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .startsWith("TypeSafe question set failed with reason UNAVAILABLE")
                .contains(
                        "service=jev-latest",
                        "provider=TypeSafe",
                        "operation=ask_native",
                        "cause=http_5xx",
                        "status=5xx",
                        "attempts=1",
                        "elapsedMs=",
                        "requestId=none")
                .containsPattern("exception=\\w+")
                .doesNotContain("mode=");
        assertNoSentinels(events);
    }

    @Test
    void clientTimeoutLogsTimeoutCause() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(
                        request -> {
                            throw new SocketTimeoutException("SENTINEL_BODY timeout detail");
                        });

        var events = capture(Level.DEBUG, () -> fixture.service().ask(SENTINEL_INPUT, spec));

        fixture.server().verify();
        var warnings = serviceWarnings(events);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("reason UNAVAILABLE", "cause=timeout", "status=none", "attempts=1");
        assertNoSentinels(events);
    }

    @Test
    void duplicateMembersLogInvalidResponseCause() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"q1":{"type":"noul","noul":0.1,"noul":0.9},"SENTINEL_BODY":{"type":"noul","noul":0.5}}}
                                """,
                                MediaType.APPLICATION_JSON));

        var events = capture(Level.DEBUG, () -> fixture.service().ask(SENTINEL_INPUT, spec));

        fixture.server().verify();
        var warnings = serviceWarnings(events);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("reason INVALID_RESPONSE", "cause=invalid_response");
        assertNoSentinels(events);
    }

    @Test
    void legacyPrefixesHoldForClassifyAndAssessFailures() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(SENTINEL_BODY));
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(SENTINEL_BODY));

        var events =
                capture(
                        Level.DEBUG,
                        () -> {
                            fixture.service()
                                    .classify(
                                            ClassificationRequest.of(SENTINEL_INPUT, ClassificationSpec.builder().asking("Which category fits?").category("a", "A").category("b", "B").build()));
                            return fixture.service()
                                    .assess(
                                            new PropositionRequest(
                                                    SENTINEL_INPUT, SENTINEL_INSTRUCTION));
                        });

        fixture.server().verify();
        var warnings = serviceWarnings(events);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0))
                .startsWith("TypeSafe classification failed with reason UNAVAILABLE")
                .contains("operation=classify", "cause=rate_limited", "status=4xx");
        assertThat(warnings.get(1))
                .startsWith("TypeSafe proposition assessment failed with reason UNAVAILABLE")
                .contains("operation=assess", "cause=http_4xx", "status=4xx");
        assertNoSentinels(events);
    }

    @Test
    void missingResponseFailsEveryOperationAsInvalidResponse() {
        var service = new TypeSafeDecisionService(new NoResponseClient());
        var category = ClassificationSpec.builder().asking("Which category fits?").category("a", "A").category("b", "B").build();

        var events =
                capture(
                        Level.DEBUG,
                        () -> {
                            assertThat(service.classify(ClassificationRequest.of(SENTINEL_INPUT, category)))
                                    .isEqualTo(new ClassificationResult.Failure(FailureReason.INVALID_RESPONSE));
                            assertThat(
                                            service.assess(
                                                    new PropositionRequest(
                                                            SENTINEL_INPUT, SENTINEL_INSTRUCTION)))
                                    .isEqualTo(new PropositionResult.Failure(FailureReason.INVALID_RESPONSE));
                            var response = service.ask(SENTINEL_INPUT, spec);
                            assertThat(response.getRequestFailure()).isEqualTo(FailureReason.INVALID_RESPONSE);
                            return response;
                        });

        var warnings = serviceWarnings(events);
        assertThat(warnings)
                .hasSize(3)
                .allSatisfy(
                        warning ->
                                assertThat(warning)
                                        .contains(
                                                "reason INVALID_RESPONSE",
                                                "cause=invalid_response",
                                                "exception=IllegalArgumentException"));
        assertNoSentinels(events);
    }

    @Test
    void successfulAskLogsNoContentUpToDebug() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withSuccess(twoAnswers(), MediaType.APPLICATION_JSON));

        var events = capture(Level.DEBUG, () -> fixture.service().ask(SENTINEL_INPUT, spec));

        fixture.server().verify();
        assertThat(serviceWarnings(events)).isEmpty();
        assertNoSentinels(events);
    }

    @Test
    void traceWithCaptureOffLogsNoContent() {
        DecisionContentCapture.disable();
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withSuccess(twoAnswers(), MediaType.APPLICATION_JSON));

        var events = capture(Level.TRACE, () -> fixture.service().ask(SENTINEL_INPUT, spec));

        fixture.server().verify();
        assertThat(events).noneMatch(event -> event.getFormattedMessage().contains("response content"));
        assertNoSentinels(events);
    }

    @Test
    void captureOnWithTraceLogsTheSdkResponse() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withSuccess(twoAnswers(), MediaType.APPLICATION_JSON));
        DecisionContentCapture.enable();
        try {
            var events = capture(Level.TRACE, () -> fixture.service().ask(SENTINEL_INPUT, spec));

            fixture.server().verify();
            assertThat(events)
                    .filteredOn(event -> event.getLoggerName().equals(TypeSafeDecisionService.class.getName()))
                    .filteredOn(event -> event.getLevel() == Level.TRACE)
                    .singleElement()
                    .satisfies(
                            event ->
                                    assertThat(event.getFormattedMessage())
                                            .startsWith("TypeSafe response content")
                                            .contains("operation=ask_native", "jev-2026-09", "SENTINEL_BODY"));
        } finally {
            DecisionContentCapture.disable();
        }
    }

    @Test
    void captureOnAtDebugLogsNoContent() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withSuccess(twoAnswers(), MediaType.APPLICATION_JSON));
        DecisionContentCapture.enable();
        try {
            var events = capture(Level.DEBUG, () -> fixture.service().ask(SENTINEL_INPUT, spec));

            fixture.server().verify();
            assertNoSentinels(events);
        } finally {
            DecisionContentCapture.disable();
        }
    }

    // The unknown answer key carries a sentinel, so a captured SDK response shows provider text.
    private static String twoAnswers() {
        return """
                {"model":"jev-2026-09","answers":{
                  "q1":{"type":"noul","noul":0.8},
                  "q2":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1},"confidence":0.8},
                  "SENTINEL_BODY":{"type":"noul","noul":0.4}}}
                """;
    }

    private static List<ILoggingEvent> capture(Level level, Supplier<?> block) {
        var logger = (Logger) LoggerFactory.getLogger("com.embabel");
        var oldLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(level);
        try {
            block.get();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private static List<String> serviceWarnings(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> event.getLoggerName().equals(TypeSafeDecisionService.class.getName()))
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void assertNoSentinels(List<ILoggingEvent> events) {
        assertThat(events)
                .isNotEmpty()
                .allSatisfy(
                        event -> {
                            assertThat(event.getFormattedMessage()).doesNotContain(SENTINELS);
                            assertThat(event.getThrowableProxy()).isNull();
                        });
    }

    private static Fixture fixture() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var factory =
                new TypeSafeModelFactory(
                        TypeSafeClientOptions.defaults(),
                        () -> SENTINEL_KEY,
                        builder,
                        ObservationRegistry.NOOP,
                        TypeSafeModelFactory.DEFAULT_MODEL);
        return new Fixture(factory.build(), server);
    }

    // A client whose systemOne call returns no response.
    private static final class NoResponseClient extends TypeSafeClient {
        NoResponseClient() {
            super(
                    new TypeSafeApi("/v1/systemone", "/v1/models", RestClient.create()),
                    TypeSafeModelFactory.DEFAULT_MODEL,
                    RetryPolicy.noRetry());
        }

        @Override
        public SystemOneResponse systemOne(
                String state, Map<String, ? extends Question> questions) {
            return null;
        }
    }

    private record Fixture(DecisionService service, MockRestServiceServer server) {}
}
