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
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.LevelProbability;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.RatingStatistic;
import com.embabel.agent.typesafe.internal.GuardedTypeSafeApi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Runs the support-triage spec against the hosted Jev model.
 *
 * <p>The test runs only when {@code TYPESAFE_SMOKE_TEST=true} and {@code TYPESAFE_API_KEY} are
 * set. The credential reaches the factory through its key supplier and is not read, printed or
 * logged by this test. Printed lines hold outcome kinds and confidence values only.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_SMOKE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class TypeSafeJevSmokeTest {
    private static final double TOLERANCE = 1.0e-6d;

    // The input is unambiguous on all three questions, so the expected answers are stable.
    private static final String INPUT =
            "This is the third month in a row you charged my card twice for the same invoice."
                    + " I want the duplicate refunded today or I am cancelling my account."
                    + " I am furious that nobody has answered my emails.";

    private final PropositionQuestionSpec urgent =
            Questions.named("urgent")
                    .proposition("Does the customer need a response today?")
                    .build();

    private final ChoiceQuestionSpec department =
            Questions.named("department")
                    .choice("Which team should handle this message?")
                    .option("billing", "Payments, charges, invoices and refunds")
                    .option("technical", "Bugs, outages and integrations")
                    .option("sales", "New purchases, upgrades and pricing questions")
                    .build();

    private final RatingQuestionSpec frustration =
            Questions.named("frustration")
                    .rating("How frustrated is the customer?")
                    .level("calm", "Calm")
                    .level("frustrated", "Frustrated")
                    .level("very_angry", "Very angry")
                    .build();

    private final DecisionService service =
            new TypeSafeModelFactory(() -> System.getenv("TYPESAFE_API_KEY")).build();

    @Test
    void supportTriageSpecRunsInOneNativeCall() {
        var spec = DecisionSpec.of(urgent, department, frustration);

        var calls = countSystemOneCalls(() -> service.ask(INPUT, spec));
        var response = calls.response();

        assertThat(calls.count()).as("provider calls").isEqualTo(1);
        assertThat(response.getRequestFailure()).isNull();

        var proposition = response.answer(urgent);
        print("urgent", proposition);
        assertThat(proposition)
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class,
                        answered -> {
                            assertThat(answered.getAnswer()).isTrue();
                            assertThat(answered.getPTrue()).isBetween(0.5d, 1.0d);
                        });

        var choice = response.answer(department);
        print("department", choice);
        assertThat(choice)
                .isInstanceOfSatisfying(
                        ClassificationResult.Selected.class,
                        selected -> {
                            assertThat(selected.getCategoryId()).isEqualTo("billing");
                            assertThat(selected.getConfidence()).isBetween(0.0d, 1.0d);
                        });

        var rating = response.answer(frustration);
        print("frustration", rating);
        assertThat(rating)
                .isInstanceOfSatisfying(
                        RatingResult.Answered.class,
                        answered -> {
                            assertThat(answered.getSelectedLevelId()).isNull();
                            assertThat(answered.getScore()).isNotNull();
                            assertThat(answered.getScore().getStatistic())
                                    .isEqualTo(RatingStatistic.EXPECTED_LEVEL_INDEX);
                            // Level index 1 is Frustrated and 2 is Very angry.
                            assertThat(answered.getScore().getValue()).isBetween(1.0d, 2.0d);
                            assertThat(answered.getDistribution())
                                    .extracting(LevelProbability::getLevelId)
                                    .containsExactly("calm", "frustrated", "very_angry");
                            assertThat(
                                            answered.getDistribution().stream()
                                                    .mapToDouble(LevelProbability::getProbability)
                                                    .sum())
                                    .isCloseTo(1.0d, within(TOLERANCE));
                            assertThat(answered.getConfidence()).isBetween(0.0d, 1.0d);
                        });
    }

    @Test
    void singleQuestionMethodsAnswerOnTheSameModel() {
        var assessment =
                service.assess(new PropositionRequest(INPUT, "The customer asks for a refund."));
        print("assess", assessment);
        assertThat(assessment)
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class,
                        answered -> assertThat(answered.getAnswer()).isTrue());

        var classification =
                service.classify(
                        ClassificationRequest.of(INPUT, ClassificationSpec.builder().asking("Which category fits?").category("billing", "Payments, charges and refunds").category("technical", "Bugs and outages").build()));
        print("classify", classification);
        assertThat(classification)
                .isInstanceOfSatisfying(
                        ClassificationResult.Selected.class,
                        selected -> assertThat(selected.getCategoryId()).isEqualTo("billing"));
    }

    // Prints the outcome kind and its confidence value. Category ids, level ids, input and
    // provider text are left out.
    private static void print(String question, Object outcome) {
        String confidence =
                switch (outcome) {
                    case PropositionResult.Answered answered -> "pTrue=" + answered.getPTrue();
                    case ClassificationResult.Selected selected ->
                            "confidence=" + selected.getConfidence();
                    case RatingResult.Answered answered ->
                            "confidence=" + answered.getConfidence();
                    default -> "confidence=none";
                };
        System.out.println(
                "Jev smoke " + question + ": " + outcome.getClass().getSimpleName() + " " + confidence);
    }

    // The guarded transport logs one DEBUG line per provider operation, which counts the calls.
    private static Calls countSystemOneCalls(Supplier<DecisionResponse> block) {
        var logger = (Logger) LoggerFactory.getLogger(GuardedTypeSafeApi.class);
        var oldLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        DecisionResponse response;
        try {
            response = block.get();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
        var count =
                appender.list.stream()
                        .filter(event -> event.getFormattedMessage().contains("operation=systemone"))
                        .count();
        return new Calls(response, count);
    }

    private record Calls(DecisionResponse response, long count) {}
}
