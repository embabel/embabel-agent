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
package com.embabel.common.ai.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;

import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

class DecisionProjectionJavaTest {

    private static final ModelProvenance JEV = new ModelProvenance("jev-latest", "typesafe");

    private final PropositionQuestionSpec urgent = Questions.named("is_urgent")
        .proposition("Does this convey urgency?")
        .build();

    private final ChoiceQuestionSpec department = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .option("sales", "Pricing, upgrades, new accounts")
        .build();

    private final RatingQuestionSpec frustration = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Frustrated")
        .level("Very angry")
        .build();

    private final DecisionSpec spec = DecisionSpec.of(urgent, department, frustration);

    record SupportRoute(boolean is_urgent, String department) {
    }

    private DecisionResponse answered() {
        return DecisionResponse.builder(spec)
            .answer(urgent, new PropositionResult.Answered(true, JEV, 0.93))
            .answer(department, new ClassificationResult.Selected("billing", JEV, 0.91))
            .answer(frustration, new RatingResult.Answered(JEV, "Frustrated"))
            .build();
    }

    @Test
    void projectsToARecordByComponentName() {
        var response = answered();
        var projection = DecisionProjection.of(response, SupportRoute.class);
        assertEquals(new SupportRoute(true, "billing"), projection.getValue());
        assertSame(response, projection.getResponse());
    }

    @Test
    void projectsToAGenericMapViaTypeReference() {
        var response = answered();
        var projection = DecisionProjection.of(response, new TypeReference<Map<String, Object>>() {
        });
        assertEquals(
            Map.of("is_urgent", true, "department", "billing", "frustration", "Frustrated"),
            projection.getValue());
        assertSame(response, projection.getResponse());
    }

    @Test
    void inconclusivePropositionIsRejectedByName() {
        var response = DecisionResponse.builder(spec)
            .answer(urgent, new PropositionResult.Inconclusive(JEV))
            .answer(department, new ClassificationResult.Selected("billing", JEV))
            .answer(frustration, new RatingResult.Answered(JEV, "Frustrated"))
            .build();

        var ex = assertThrows(DecisionProjectionException.class, () -> DecisionProjection.answeredValues(response));
        assertEquals(List.of("is_urgent"), ex.getQuestions());
        assertTrue(ex.getMessage().contains("'is_urgent'"));
    }

    @Test
    void noMatchChoiceIsRejectedByName() {
        var response = DecisionResponse.builder(spec)
            .answer(urgent, new PropositionResult.Answered(true, JEV))
            .answer(department, new ClassificationResult.NoMatch(JEV))
            .answer(frustration, new RatingResult.Answered(JEV, "Frustrated"))
            .build();

        var ex = assertThrows(DecisionProjectionException.class, () -> DecisionProjection.answeredValues(response));
        assertEquals(List.of("department"), ex.getQuestions());
        assertTrue(ex.getMessage().contains("'department'"));
    }

    @Test
    void failureOutcomesAreRejectedByName() {
        var response = DecisionResponse.failed(spec, FailureReason.UNAVAILABLE);

        var ex = assertThrows(DecisionProjectionException.class, () -> DecisionProjection.answeredValues(response));
        assertEquals(List.of("is_urgent", "department", "frustration"), ex.getQuestions());
    }

    @Test
    void scalarOnlyRatingIsRejectedByName() {
        var response = DecisionResponse.builder(spec)
            .answer(urgent, new PropositionResult.Answered(true, JEV))
            .answer(department, new ClassificationResult.Selected("billing", JEV))
            .answer(frustration, new RatingResult.Answered(
                JEV, null, List.of(), new RatingScore(0.6, RatingStatistic.EXPECTED_LEVEL_INDEX), null))
            .build();

        var ex = assertThrows(DecisionProjectionException.class, () -> DecisionProjection.answeredValues(response));
        assertEquals(List.of("frustration"), ex.getQuestions());
        assertTrue(ex.getMessage().contains("'frustration'"));
    }

    @Test
    void theOriginalResponseIsUnchangedAndAvailableFromTheProjection() {
        var response = answered();
        var projection = DecisionProjection.of(response, SupportRoute.class);
        assertSame(response, projection.getResponse());
        assertEquals(response.getAnswers(), projection.getResponse().getAnswers());
    }

    record WrongShape(int department) {
    }

    @Test
    void mappingFailureWrapsTheJacksonExceptionWithNoQuestionsListed() {
        var response = answered();
        var ex = assertThrows(DecisionProjectionException.class,
            () -> DecisionProjection.of(response, WrongShape.class));
        assertTrue(ex.getQuestions().isEmpty());
        assertNotNull(ex.getCause());
    }

    // tag::converter[]
    static class SupportRouteConverter implements Converter<DecisionResponse, SupportRoute> {

        private final PropositionQuestionSpec urgent;
        private final ChoiceQuestionSpec department;

        SupportRouteConverter(PropositionQuestionSpec urgent, ChoiceQuestionSpec department) {
            this.urgent = urgent;
            this.department = department;
        }

        @Override
        public SupportRoute convert(DecisionResponse response) {
            boolean isUrgent = switch (response.answer(urgent)) {
                case PropositionResult.Answered answered -> answered.getAnswer();
                // Without an answer, a person should look at the ticket soon.
                case PropositionResult.Inconclusive inconclusive -> true;
                case PropositionResult.Failure failure -> true;
            };
            String team = switch (response.answer(department)) {
                case ClassificationResult.Selected selected -> selected.getCategoryId();
                case ClassificationResult.NoMatch noMatch -> "general";
                case ClassificationResult.Inconclusive inconclusive -> "triage-queue";
                case ClassificationResult.Failure failure -> "triage-queue";
            };
            return new SupportRoute(isUrgent, team);
        }
    }
    // end::converter[]

    @Test
    void springConverterHandlesEachOutcomeWithTypedLookup() {
        var converter = new SupportRouteConverter(urgent, department);
        assertEquals(new SupportRoute(true, "billing"), converter.convert(answered()));

        var notSelected = DecisionResponse.builder(spec)
            .answer(urgent, new PropositionResult.Answered(false, JEV))
            .answer(department, new ClassificationResult.NoMatch(JEV))
            .answer(frustration, new RatingResult.Answered(JEV, "Calm"))
            .build();
        assertEquals(new SupportRoute(false, "general"), converter.convert(notSelected));

        var unanswered = DecisionResponse.builder(spec)
            .answer(urgent, new PropositionResult.Inconclusive(JEV))
            .answer(department, new ClassificationResult.Failure(FailureReason.UNAVAILABLE))
            .answer(frustration, new RatingResult.Answered(JEV, "Calm"))
            .build();
        assertEquals(new SupportRoute(true, "triage-queue"), converter.convert(unanswered));
    }

    record WithUnansweredComponent(boolean is_urgent, String department, String priority) {
    }

    @Test
    void theDefaultMapperRejectsARecordComponentWithNoAnsweredValue() {
        var response = answered();
        var ex = assertThrows(DecisionProjectionException.class,
            () -> DecisionProjection.of(response, WithUnansweredComponent.class));
        assertTrue(ex.getMessage().contains("WithUnansweredComponent"));
        assertNotNull(ex.getCause());
    }
}
