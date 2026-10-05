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
package com.embabel.agent.typesafe.example;

import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.FEEDBACK;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.JEV_MODEL;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.REVIEW_MODEL;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.REVIEW_PROVIDER;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.ROLES_AND_QUESTIONS;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.SENTINEL_BODY;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.SENTINEL_INPUT;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.SYSTEM_ONE_URI;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.TRIAGE;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.answerPrompts;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.askEachQuestion;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.askTriage;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.assertBoundedTags;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.assertFailureLogLines;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.assertQuestionSetAnswers;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.assertNoSentinels;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.assertPromptedAnswers;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.assertPerQuestionAnswers;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.assertUnavailable;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.capture;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.expectQuestionSetAnswers;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.expectPerQuestionAnswers;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.operationContext;
import static com.embabel.agent.typesafe.example.DecisionServicesSpringExampleTest.verifyOnePrompt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.agent.typesafe.TypeSafeModelFactory;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.observation.ObservedDecisionService;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.HttpStatus;

import java.util.stream.Stream;

/**
 * Observations of the assembled decision services: one logical {@code embabel.ai.ask} observation
 * per ask, with one {@code embabel.ai.decision} child per provider call, and the metric, span and
 * log trail a failing provider leaves.
 */
@ResourceLock("DecisionContentCapture")
class DecisionServicesObservationTest {
    private final DecisionServicesSpringExampleTest.Fixture fixture = new DecisionServicesSpringExampleTest.Fixture();

    @Test
    void questionSetJevAskRecordsOneAskAndOneProviderObservation() {
        expectQuestionSetAnswers(fixture.server);

        fixture.run(context -> assertQuestionSetAnswers(askTriage(context, FEEDBACK)));

        fixture.server.verify();
        var ask = assertOneAskWithOneQuestionSetCall();
        assertThat(ask.getLowCardinalityKeyValue("service").getValue()).isEqualTo(JEV_MODEL);
        assertThat(ask.getLowCardinalityKeyValue("provider").getValue()).isEqualTo(TypeSafeModelFactory.PROVIDER);
        assertThat(ask.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("complete");
        assertThat(fixture.recorder.events).hasSize(3);
        var answered =
                fixture.meters.find("embabel.ai.ask.answer.proposition.answered")
                        .tag("service", JEV_MODEL)
                        .tag("outcome", "complete")
                        .counter();
        assertThat(answered).isNotNull();
        assertThat(answered.count()).isEqualTo(1.0d);
        assertBoundedTags(fixture.meters);
    }

    @Test
    void jevQuestionHooksRecordOneProviderObservationEach() {
        expectPerQuestionAnswers(fixture.server);

        fixture.run(context -> assertPerQuestionAnswers(askEachQuestion(context)));

        fixture.server.verify();
        assertThat(fixture.recorder.named("embabel.ai.ask")).isEmpty();
        assertThat(fixture.recorder.named("embabel.ai.decision"))
                .extracting(call -> call.getLowCardinalityKeyValue("operation").getValue())
                .containsExactly("assess", "rate");
        assertThat(fixture.recorder.named("embabel.ai.classification"))
                .extracting(call -> call.getLowCardinalityKeyValue("operation").getValue())
                .containsExactly("classify");
    }

    @Test
    void promptedRoleRecordsOneAskAndOneProviderObservation() {
        answerPrompts(fixture);

        fixture.run(
                context -> assertPromptedAnswers(
                        context.getBean(DecisionServiceRegistry.class)
                                .decisions()
                                .byRole("dice-revision-review")
                                .ask(FEEDBACK, TRIAGE)));

        verifyOnePrompt(fixture);
        var ask = assertOneAskWithOneQuestionSetCall();
        assertThat(ask.getLowCardinalityKeyValue("provider").getValue()).isEqualTo(REVIEW_PROVIDER);
    }

    @Test
    void factoryBuiltPromptedServiceRecordsOneAskAndOneProviderObservation() {
        answerPrompts(fixture);

        fixture.run(
                context -> {
                    LlmDecisionServiceFactory factory = context.getBean(LlmDecisionServiceFactory.class);
                    DecisionService review = factory.decisionService(fixture.reviewModel);
                    assertThat(review).isInstanceOf(ObservedDecisionService.class);
                    assertPromptedAnswers(review.ask(FEEDBACK, TRIAGE));
                });

        verifyOnePrompt(fixture);
        var ask = assertOneAskWithOneQuestionSetCall();
        assertThat(ask.getLowCardinalityKeyValue("service").getValue()).isEqualTo(REVIEW_MODEL);
        assertThat(ask.getLowCardinalityKeyValue("provider").getValue()).isEqualTo(REVIEW_PROVIDER);
        assertThat(ask.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("complete");
    }

    @Test
    void unavailableJevLeavesALogMetricAndSpanTrailWithoutPayload() {
        fixture.server
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SENTINEL_BODY));

        var events = capture(() -> fixture.run(context -> assertUnavailable(askTriage(context, SENTINEL_INPUT))));

        fixture.server.verify();
        verifyNoInteractions(fixture.llmOperations);

        // Log: one provider WARN with the cause and one execution WARN with the request failure.
        assertFailureLogLines(events);

        // Metric: the ask timer carries the outcome, the service and the provider.
        var askTimer =
                fixture.meters.find("embabel.ai.ask")
                        .tag("outcome", "request_failure")
                        .tag("service", JEV_MODEL)
                        .tag("provider", TypeSafeModelFactory.PROVIDER)
                        .timer();
        assertThat(askTimer).isNotNull();
        assertThat(askTimer.count()).isEqualTo(1L);

        // Span: the ask holds one question-set call, and both carry the error marker.
        var ask = assertOneAskWithOneQuestionSetCall();
        var questionSetCall = fixture.recorder.named("embabel.ai.decision").get(0);
        assertThat(questionSetCall.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("request_failure");
        assertThat(questionSetCall.getError()).isNotNull();
        assertThat(questionSetCall.getError().getMessage()).isEqualTo("request_failure");
        assertThat(ask.getError()).isNotNull();

        // No payload reaches a tag, a contextual name, an error or a log line, and no role or
        // question name becomes a tag value.
        assertBoundedTags(fixture.meters);
        for (Observation.Context context : fixture.recorder.stopped) {
            Stream.concat(
                            context.getLowCardinalityKeyValues().stream(),
                            context.getHighCardinalityKeyValues().stream())
                    .map(KeyValue::getValue)
                    .forEach(value -> assertThat(value).doesNotContain("SENTINEL").isNotIn(ROLES_AND_QUESTIONS));
            assertThat(String.valueOf(context.getContextualName())).doesNotContain("SENTINEL");
            if (context.getError() != null) {
                assertThat(String.valueOf(context.getError().getMessage())).doesNotContain("SENTINEL");
            }
        }
        assertThat(fixture.recorder.events)
                .allSatisfy(event -> assertThat(event.getContextualName()).doesNotContain("SENTINEL"));
        assertNoSentinels(events);
    }

    @Test
    void askSelectedInsideAnActionIsAChildOfTheAction() {
        expectQuestionSetAnswers(fixture.server);

        fixture.run(
                context -> {
                    var operation = operationContext(context);
                    // The action observation is current when the service is selected. The ask runs
                    // after the action's scope has closed.
                    var action = Observation.start("test.action", fixture.observations);
                    DecisionService triage;
                    try (var ignored = action.openScope()) {
                        triage = operation.ai().decisions().byRole("support-triage");
                    }
                    assertQuestionSetAnswers(triage.ask(FEEDBACK, TRIAGE));
                    action.stop();
                });

        fixture.server.verify();
        var ask = assertOneAskWithOneQuestionSetCall();
        var action = fixture.recorder.named("test.action").get(0);
        assertThat(ask.getParentObservation().getContextView()).isSameAs(action);
    }

    /** Checks one logical ask with one question-set provider call as its child, and returns the ask. */
    private Observation.Context assertOneAskWithOneQuestionSetCall() {
        var asks = fixture.recorder.named("embabel.ai.ask");
        var calls = fixture.recorder.named("embabel.ai.decision");
        assertThat(asks).hasSize(1);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getLowCardinalityKeyValue("operation").getValue()).isEqualTo("ask_question_set");
        assertThat(calls.get(0).getParentObservation().getContextView()).isSameAs(asks.get(0));
        assertThat(asks.get(0).getLowCardinalityKeyValue("execution_mode")).isNull();
        return asks.get(0);
    }
}
