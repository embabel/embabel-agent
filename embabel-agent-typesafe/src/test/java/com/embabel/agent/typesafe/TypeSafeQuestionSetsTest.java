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

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.LevelProbability;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.RatingScore;
import com.embabel.common.ai.decision.RatingStatistic;
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.UnknownAnswer;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

class TypeSafeQuestionSetsTest {

    private static final String SERVICE = "jev-latest";
    private static final ModelProvenance PROVENANCE =
            new ModelProvenance("jev-latest", "TypeSafe", null, "req-1");
    private static final ChoiceAnswer BILLING =
            new ChoiceAnswer("billing", Map.of("billing", 0.9, "technical", 0.1), 0.8);
    private static final ScoreAnswer ANGRY_ISH =
            new ScoreAnswer(
                    1.6,
                    Map.of(0, JsonContent.of("Calm"), 1, JsonContent.of("Frustrated"), 2, JsonContent.of("Very angry")),
                    Map.of(0, 0.1, 1, 0.2, 2, 0.7),
                    0.4);

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
                    .level("Calm")
                    .level("Frustrated")
                    .level("Very angry")
                    .build();

    private final DecisionSpec spec = DecisionSpec.of(urgent, department, frustration);

    private static Map<String, Answer> answers(Object... keysAndAnswers) {
        var map = new LinkedHashMap<String, Answer>();
        for (int i = 0; i < keysAndAnswers.length; i += 2) {
            map.put((String) keysAndAnswers[i], (Answer) keysAndAnswers[i + 1]);
        }
        return map;
    }

    private DecisionResponse map(Map<String, Answer> answers) {
        return TypeSafeQuestionSets.response(
                spec, new SystemOneResponse("jev-latest", answers, null, "req-1"), PROVENANCE, SERVICE);
    }

    private static List<String> warnings(Supplier<?> block) {
        var logger = (Logger) LoggerFactory.getLogger(DecisionResponseAssembler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            block.get();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        var lines = new ArrayList<String>();
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                lines.add(event.getFormattedMessage());
            }
        }
        return lines;
    }

    private static final ClassificationResult CHOICE_FAILED =
            new ClassificationResult.Failure(FailureReason.INVALID_RESPONSE);
    private static final RatingResult RATING_FAILED = new RatingResult.Failure(FailureReason.INVALID_RESPONSE);
    private static final PropositionResult PROPOSITION_FAILED =
            new PropositionResult.Failure(FailureReason.INVALID_RESPONSE);

    @Test
    void questionsAreKeyedQ1ToQnInSpecOrderAndCarryNoCallerNames() {
        Map<String, Question> questions = TypeSafeQuestionSets.questions(spec);

        assertThat(questions.keySet()).containsExactly("q1", "q2", "q3");
        assertThat(questions)
                .containsEntry("q1", Noul.of("Does this convey urgency?"))
                .containsEntry(
                        "q2",
                        Choice.builder()
                                .instructions("Which team should handle this?")
                                .option("billing", "Payments, invoicing, refunds")
                                .option("technical", "Bugs, outages, integrations")
                                .build())
                .containsEntry(
                        "q3",
                        Score.builder()
                                .instructions("How frustrated is the customer?")
                                .level("Calm")
                                .level("Frustrated")
                                .level("Very angry")
                                .build());
        assertThat(((Choice) questions.get("q2")).criteria().keySet()).containsExactly("billing", "technical");

        var serialized =
                JsonMapper.builder()
                        .build()
                        .writeValueAsString(new SystemOneRequest(JsonContent.of("ticket text"), null, questions));
        assertThat(serialized).doesNotContain("caller_").contains("\"q1\"", "\"q2\"", "\"q3\"");
    }

    @Test
    void mapsEveryKindWithSharedProvenance() {
        var response = map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", ANGRY_ISH));

        assertThat(response.getRequestFailure()).isNull();
        assertThat(response.answer(urgent)).isEqualTo(new PropositionResult.Answered(true, PROVENANCE, 0.8));
        assertThat(response.answer(department))
                .isEqualTo(new ClassificationResult.Selected("billing", PROVENANCE, 0.8));
        assertThat(((RatingResult.Answered) response.answer(frustration)).getProvenance()).isEqualTo(PROVENANCE);
    }

    /**
     * The rubric and numbers come from the example in typesafe-java-sdk 0.2.0. Score.java states
     * that level 0 is the first criteria entry, and ScoreAnswer.java keys the legend and the
     * probabilities by level index and describes 1.6 on Calm/Frustrated/Very angry.
     */
    @Test
    void goldenScoreFromTheSdkExample() {
        var response = map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", ANGRY_ISH));

        var rating = (RatingResult.Answered) response.answer(frustration);
        assertThat(rating.getScore()).isEqualTo(new RatingScore(1.6, RatingStatistic.EXPECTED_LEVEL_INDEX));
        assertThat(rating.getScore().getValue()).isEqualTo(1.6);
        assertThat(rating.getSelectedLevelId()).isNull();
        assertThat(rating.getDistribution())
                .containsExactly(
                        new LevelProbability("Calm", 0.1),
                        new LevelProbability("Frustrated", 0.2),
                        new LevelProbability("Very angry", 0.7));
        assertThat(rating.getConfidence()).isEqualTo(0.4);
    }

    @Test
    void noulMidpointIsInconclusiveAndOtherValuesAnswer() {
        assertThat(map(answers("q1", new NoulAnswer(0.5), "q2", BILLING, "q3", ANGRY_ISH)).answer(urgent))
                .isEqualTo(new PropositionResult.Inconclusive(PROVENANCE));
        assertThat(map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", ANGRY_ISH)).answer(urgent))
                .isEqualTo(new PropositionResult.Answered(true, PROVENANCE, 0.8));
        assertThat(map(answers("q1", new NoulAnswer(0.2), "q2", BILLING, "q3", ANGRY_ISH)).answer(urgent))
                .isEqualTo(new PropositionResult.Answered(false, PROVENANCE, 0.2));
    }

    @Test
    void noulOutsideZeroToOneFailsOnlyThatQuestion() {
        var response = map(answers("q1", new NoulAnswer(1.5), "q2", BILLING, "q3", ANGRY_ISH));

        assertThat(response.answer(urgent)).isEqualTo(PROPOSITION_FAILED);
        assertThat(response.answer(department)).isInstanceOf(ClassificationResult.Selected.class);
    }

    @Test
    void choiceWithZeroConfidenceIsInconclusive() {
        var flat = new ChoiceAnswer("billing", Map.of("billing", 0.5, "technical", 0.5), 0.0);

        assertThat(map(answers("q1", new NoulAnswer(0.8), "q2", flat, "q3", ANGRY_ISH)).answer(department))
                .isEqualTo(new ClassificationResult.Inconclusive(PROVENANCE));
    }

    @Test
    void choiceProbabilitiesMissingAnOptionFailOnlyThatQuestion() {
        var partial = new ChoiceAnswer("billing", Map.of("billing", 1.0), 0.9);
        var response = map(answers("q1", new NoulAnswer(0.8), "q2", partial, "q3", ANGRY_ISH));

        assertThat(response.answer(department)).isEqualTo(CHOICE_FAILED);
        assertThat(response.answer(urgent)).isInstanceOf(PropositionResult.Answered.class);
        assertThat(response.answer(frustration)).isInstanceOf(RatingResult.Answered.class);
    }

    @Test
    void choiceProbabilitiesThatDoNotSumToOneFailOnlyThatQuestion() {
        var unnormalized = new ChoiceAnswer("billing", Map.of("billing", 0.6, "technical", 0.6), 0.9);

        assertThat(map(answers("q1", new NoulAnswer(0.8), "q2", unnormalized, "q3", ANGRY_ISH)).answer(department))
                .isEqualTo(CHOICE_FAILED);
    }

    @Test
    void choiceLabelOutsideTheOptionsFailsOnlyThatQuestion() {
        var outside = new ChoiceAnswer("SENTINEL_LABEL", Map.of("billing", 0.5, "technical", 0.5), 0.9);
        var lines = new ArrayList<String>();
        lines.addAll(
                warnings(
                        () -> {
                            var response = map(answers("q1", new NoulAnswer(0.8), "q2", outside, "q3", ANGRY_ISH));
                            assertThat(response.answer(department)).isEqualTo(CHOICE_FAILED);
                            assertThat(response.answer(frustration)).isInstanceOf(RatingResult.Answered.class);
                            return response;
                        }));

        assertThat(lines).singleElement().asString().contains("caller_department=OUT_OF_DOMAIN").doesNotContain("SENTINEL");
    }

    @Test
    void scoreProbabilitiesKeyedFromOneFailOnlyThatQuestion() {
        var shifted =
                new ScoreAnswer(
                        1.6,
                        Map.of(1, JsonContent.of("Calm"), 2, JsonContent.of("Frustrated"), 3, JsonContent.of("Very angry")),
                        Map.of(1, 0.1, 2, 0.2, 3, 0.7),
                        0.4);
        var lines =
                warnings(
                        () -> {
                            var response = map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", shifted));
                            assertThat(response.answer(frustration)).isEqualTo(RATING_FAILED);
                            assertThat(response.answer(department)).isInstanceOf(ClassificationResult.Selected.class);
                            return response;
                        });

        assertThat(lines).singleElement().asString().contains("caller_frustration=BAD_DISTRIBUTION");
    }

    @Test
    void scoreLegendThatDoesNotCoverTheLevelsFailsOnlyThatQuestion() {
        var shortLegend =
                new ScoreAnswer(1.6, Map.of(0, JsonContent.of("Calm")), Map.of(0, 0.1, 1, 0.2, 2, 0.7), 0.4);

        assertThat(map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", shortLegend)).answer(frustration))
                .isEqualTo(RATING_FAILED);
    }

    @Test
    void scoreAboveTheLastLevelFailsOnlyThatQuestion() {
        var high = new ScoreAnswer(2.5, ANGRY_ISH.legend(), ANGRY_ISH.probabilities(), 0.4);
        var lines =
                warnings(
                        () -> {
                            var response = map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", high));
                            assertThat(response.answer(frustration)).isEqualTo(RATING_FAILED);
                            assertThat(response.answer(urgent)).isInstanceOf(PropositionResult.Answered.class);
                            return response;
                        });

        assertThat(lines).singleElement().asString().contains("caller_frustration=OUT_OF_RANGE").doesNotContain("2.5");
    }

    @Test
    void negativeOrNonFiniteScoreFailsOnlyThatQuestion() {
        for (double value : new double[] {-0.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var bad = new ScoreAnswer(value, ANGRY_ISH.legend(), ANGRY_ISH.probabilities(), 0.4);
            assertThat(map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", bad)).answer(frustration))
                    .isEqualTo(RATING_FAILED);
        }
    }

    @Test
    void unknownKeyIsIgnoredAndSiblingsKept() {
        var lines =
                warnings(
                        () -> {
                            var response =
                                    map(answers("q1", new NoulAnswer(0.8), "q9", new NoulAnswer(0.1), "q2", BILLING, "q3", ANGRY_ISH));
                            assertThat(response.getRequestFailure()).isNull();
                            assertThat(response.answer(urgent)).isInstanceOf(PropositionResult.Answered.class);
                            assertThat(response.answer(department)).isInstanceOf(ClassificationResult.Selected.class);
                            assertThat(response.answer(frustration)).isInstanceOf(RatingResult.Answered.class);
                            return response;
                        });

        assertThat(lines).singleElement().asString().contains("unexpected answers: 1").doesNotContain("q9");
    }

    @Test
    void missingKeyFailsOnlyThatQuestion() {
        var lines =
                warnings(
                        () -> {
                            var response = map(answers("q1", new NoulAnswer(0.8), "q3", ANGRY_ISH));
                            assertThat(response.answer(department)).isEqualTo(CHOICE_FAILED);
                            assertThat(response.answer(urgent)).isInstanceOf(PropositionResult.Answered.class);
                            return response;
                        });

        assertThat(lines).singleElement().asString().contains("caller_department=MISSING");
    }

    @Test
    void noulUnderAChoiceKeyFailsThatQuestionAsWrongKind() {
        var lines =
                warnings(
                        () -> {
                            var response = map(answers("q1", new NoulAnswer(0.8), "q2", new NoulAnswer(0.9), "q3", ANGRY_ISH));
                            assertThat(response.answer(department)).isEqualTo(CHOICE_FAILED);
                            assertThat(response.answer(frustration)).isInstanceOf(RatingResult.Answered.class);
                            return response;
                        });

        assertThat(lines).singleElement().asString().contains("caller_department=WRONG_KIND");
    }

    @Test
    void unknownAnswerFailsThatQuestion() {
        var unknown = new UnknownAnswer("SENTINEL_TYPE", Map.of("SENTINEL_FIELD", "SENTINEL_VALUE"));
        var lines =
                warnings(
                        () -> {
                            var response = map(answers("q1", new NoulAnswer(0.8), "q2", BILLING, "q3", unknown));
                            assertThat(response.answer(frustration)).isEqualTo(RATING_FAILED);
                            assertThat(response.answer(department)).isInstanceOf(ClassificationResult.Selected.class);
                            return response;
                        });

        assertThat(lines).singleElement().asString().contains("caller_frustration=UNREADABLE").doesNotContain("SENTINEL");
    }

    @Test
    void tamperRightKeysWithTheOtherKindsFailOnlyThoseQuestions() {
        var lines =
                warnings(
                        () -> {
                            var response = map(answers("q1", new NoulAnswer(0.8), "q2", ANGRY_ISH, "q3", BILLING));
                            assertThat(response.getRequestFailure()).isNull();
                            assertThat(response.answer(department)).isEqualTo(CHOICE_FAILED);
                            assertThat(response.answer(frustration)).isEqualTo(RATING_FAILED);
                            assertThat(response.answer(urgent)).isInstanceOf(PropositionResult.Answered.class);
                            return response;
                        });

        assertThat(lines)
                .singleElement()
                .asString()
                .contains("caller_department=WRONG_KIND", "caller_frustration=WRONG_KIND");
    }

    @Test
    void warningsNameSpecQuestionsAndNeverProviderKeys() {
        var lines =
                warnings(
                        () ->
                                map(answers(
                                        "q1", new UnknownAnswer(null, Map.of()),
                                        "q2", new ChoiceAnswer("billing", Map.of("billing", 1.0), 0.9),
                                        "q3", new ScoreAnswer(2.5, ANGRY_ISH.legend(), ANGRY_ISH.probabilities(), 0.4),
                                        "SENTINEL_KEY", new NoulAnswer(0.1))));

        assertThat(lines)
                .containsExactly(
                        "Decision service 'jev-latest' returned answers it could not use: caller_urgent=UNREADABLE, "
                                + "caller_department=BAD_DISTRIBUTION, caller_frustration=OUT_OF_RANGE; unexpected answers: 1");
        assertThat(lines.getFirst()).doesNotContainPattern("\\bq\\d+\\b").doesNotContain("SENTINEL");
    }
}
