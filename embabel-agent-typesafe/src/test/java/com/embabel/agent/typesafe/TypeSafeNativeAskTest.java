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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionAnswer;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.QuestionKind;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.RatingStatistic;
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution;
import com.embabel.common.ai.decision.spi.PropositionAssessment;
import com.embabel.common.ai.decision.spi.RatingAssessment;

import io.micrometer.observation.ObservationRegistry;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.EnumSet;

class TypeSafeNativeAskTest {
    private static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";
    private static final String INPUT = "I was charged twice and nobody answers my emails.";

    static final String THREE_ANSWERS =
            """
            {"model":"jev-2026-09","answers":{
              "q1":{"type":"noul","noul":0.8},
              "q2":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1},"confidence":0.8},
              "q3":{"type":"score","score":1.6,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.1,"1":0.2,"2":0.7},"confidence":0.4}}}
            """;

    private final PropositionQuestionSpec urgent =
            Questions.named("caller_urgent").proposition("Does this convey urgency?").build();

    private final ChoiceQuestionSpec department =
            Questions.named("caller_department")
                    .choice("Which team should handle this?")
                    .option("billing", "Payments, invoicing, refunds")
                    .option("technical", "Bugs, outages, integrations")
                    .build();

    private final RatingQuestionSpec frustration =
            Questions.named("caller_frustration")
                    .rating("How frustrated is the customer?")
                    .level("calm", "Calm")
                    .level("frustrated", "Frustrated")
                    .level("very_angry", "Very angry")
                    .build();

    private final DecisionSpec spec = DecisionSpec.of(urgent, department, frustration);

    @Test
    void threeQuestionAskMakesOneRequestKeyedQ1ToQ3() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.q1").exists())
                .andExpect(jsonPath("$.questions.q2").exists())
                .andExpect(jsonPath("$.questions.q3").exists())
                .andExpect(jsonPath("$.questions.q4").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("caller_"))))
                .andRespond(
                        withSuccess(THREE_ANSWERS, MediaType.APPLICATION_JSON)
                                .header("x-typesafe-request-id", "request-42"));

        var response = fixture.factory().build().ask(INPUT, spec);

        fixture.server().verify();
        assertThat(response.getRequestFailure()).isNull();
        assertThat(response.getAnswers())
                .extracting(DecisionAnswer::getName)
                .containsExactly("caller_urgent", "caller_department", "caller_frustration");
        assertThat(response.answer(urgent))
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class,
                        answered -> {
                            assertThat(answered.getAnswer()).isTrue();
                            assertThat(answered.getPTrue()).isEqualTo(0.8d);
                            assertThat(answered.getProvenance().getModelName()).isEqualTo("jev-2026-09");
                            assertThat(answered.getProvenance().getProvider())
                                    .isEqualTo(TypeSafeModelFactory.PROVIDER);
                            assertThat(answered.getProvenance().getRequestId()).isEqualTo("request-42");
                        });
        assertThat(response.answer(department))
                .isInstanceOfSatisfying(
                        ClassificationResult.Selected.class,
                        selected -> {
                            assertThat(selected.getCategoryId()).isEqualTo("billing");
                            assertThat(selected.getProvenance().getRequestId()).isEqualTo("request-42");
                        });
        assertThat(response.answer(frustration))
                .isInstanceOfSatisfying(
                        RatingResult.Answered.class,
                        answered -> {
                            assertThat(answered.getScore().getValue()).isEqualTo(1.6d);
                            assertThat(answered.getScore().getStatistic())
                                    .isEqualTo(RatingStatistic.EXPECTED_LEVEL_INDEX);
                            assertThat(answered.getSelectedLevelId()).isNull();
                            assertThat(answered.getProvenance().getRequestId()).isEqualTo("request-42");
                        });
    }

    @Test
    void unavailableProviderFailsTheWholeRequest() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var response = fixture.factory().build().ask(INPUT, spec);

        fixture.server().verify();
        assertThat(response.getRequestFailure()).isEqualTo(FailureReason.UNAVAILABLE);
        assertThat(response.answer(urgent)).isEqualTo(new PropositionResult.Failure(FailureReason.UNAVAILABLE));
        assertThat(response.answer(department))
                .isEqualTo(new ClassificationResult.Failure(FailureReason.UNAVAILABLE));
        assertThat(response.answer(frustration)).isEqualTo(new RatingResult.Failure(FailureReason.UNAVAILABLE));
    }

    @Test
    void duplicateJsonMembersAreAnInvalidResponse() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"q1":{"type":"noul","noul":0.1,"noul":0.9}}}
                                """,
                                MediaType.APPLICATION_JSON));

        var response = fixture.factory().build().ask(INPUT, spec);

        fixture.server().verify();
        assertThat(response.getRequestFailure()).isEqualTo(FailureReason.INVALID_RESPONSE);
        assertThat(response.getAnswers()).hasSize(3);
    }

    @Test
    void classifyAndRateMakeOneRequestEach() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.classification.type").value("choice"))
                .andExpect(jsonPath("$.questions.classification.instructions")
                        .value("Which team should handle this?"))
                .andExpect(jsonPath("$.questions.classification.criteria.technical").exists())
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"classification":{"type":"choice","choice":"technical","probabilities":{"billing":0.3,"technical":0.7},"confidence":0.5}}}
                                """,
                                MediaType.APPLICATION_JSON));
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.q1.type").value("score"))
                .andExpect(jsonPath("$.questions.q2").doesNotExist())
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"q1":{"type":"score","score":0.2,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.8,"1":0.2,"2":0.0},"confidence":0.6}}}
                                """,
                                MediaType.APPLICATION_JSON));
        var service = fixture.rawService();

        var choice = service.classify(ClassificationRequest.of(INPUT, ClassificationSpec.of(department)));
        var rating = service.rate(INPUT, frustration);

        fixture.server().verify();
        assertThat(choice)
                .isInstanceOfSatisfying(
                        ClassificationResult.Selected.class,
                        selected -> assertThat(selected.getCategoryId()).isEqualTo("technical"));
        assertThat(rating)
                .isInstanceOfSatisfying(
                        RatingResult.Answered.class,
                        answered -> assertThat(answered.getScore().getValue()).isEqualTo(0.2d));
    }

    @Test
    void questionHooksMakeOneRequestPerQuestion() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.proposition.type").value("noul"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"proposition":{"type":"noul","noul":0.2}}}
                                """,
                                MediaType.APPLICATION_JSON));
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.classification.type").value("choice"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"classification":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1},"confidence":0.8}}}
                                """,
                                MediaType.APPLICATION_JSON));
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.q1.type").value("score"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"q1":{"type":"score","score":2.0,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.0,"1":0.0,"2":1.0},"confidence":1.0}}}
                                """,
                                MediaType.APPLICATION_JSON));

        var service = fixture.factory().build();

        var proposition = ((PropositionAssessment) service).assess(INPUT, urgent);
        var choice = service.classify(ClassificationRequest.of(INPUT, ClassificationSpec.of(department)));
        var rating = ((RatingAssessment) service).rate(INPUT, frustration);

        fixture.server().verify();
        assertThat(proposition)
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class, answered -> assertThat(answered.getAnswer()).isFalse());
        assertThat(choice).isInstanceOf(ClassificationResult.Selected.class);
        assertThat(rating).isInstanceOf(RatingResult.Answered.class);
    }

    @Test
    void descriptorAndHooksAgree() {
        var fixture = fixture();
        var raw = fixture.rawService();
        var capabilities = raw.capabilities();

        assertThat(capabilities.getQuestionKinds()).isEqualTo(EnumSet.allOf(QuestionKind.class));
        assertThat(capabilities.getMaxQuestions()).isNull();
        assertThat(capabilities.getMaxInputCharacters()).isNull();
        assertThat(raw)
                .isInstanceOf(NativeQuestionSetExecution.class)
                .isInstanceOf(PropositionAssessment.class)
                .isInstanceOf(RatingAssessment.class);
        assertThat(fixture.factory().build().capabilities()).isEqualTo(capabilities);
    }

    static Fixture fixture() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var factory =
                new TypeSafeModelFactory(
                        TypeSafeClientOptions.defaults(),
                        () -> "test-key",
                        builder,
                        ObservationRegistry.NOOP,
                        TypeSafeModelFactory.DEFAULT_MODEL);
        var clients =
                new TypeSafeClientFactory(
                        TypeSafeClientOptions.defaults(), () -> "test-key", builder, ObservationRegistry.NOOP);
        return new Fixture(factory, server, clients);
    }

    record Fixture(TypeSafeModelFactory factory, MockRestServiceServer server, TypeSafeClientFactory clients) {
        // The hooks are called on the unwrapped service, which implements them directly.
        TypeSafeDecisionService rawService() {
            return new TypeSafeDecisionService(clients.build(TypeSafeModelFactory.DEFAULT_MODEL));
        }
    }
}
