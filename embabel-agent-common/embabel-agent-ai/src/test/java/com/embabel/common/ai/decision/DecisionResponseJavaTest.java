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

import java.util.List;

class DecisionResponseJavaTest {

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

    private DecisionResponse answered() {
        return DecisionResponse.builder(DecisionSpec.of(urgent, department, frustration))
            .answer(urgent, new PropositionResult.Answered(true, JEV, 0.93))
            .answer(department, new ClassificationResult.Selected("billing", JEV, 0.91))
            .answer(frustration, new RatingResult.Answered(JEV, "Frustrated"))
            .build();
    }

    @Test
    void typedLookupReturnsTheResultTypeOfEachQuestion() {
        var response = answered();

        // tag::typed[]
        PropositionResult urgency = response.answer(urgent);
        ClassificationResult team = response.answer(department);
        RatingResult anger = response.answer(frustration);
        // end::typed[]

        var answeredUrgency = assertInstanceOf(PropositionResult.Answered.class, urgency);
        assertTrue(answeredUrgency.getAnswer());
        var selected = assertInstanceOf(ClassificationResult.Selected.class, team);
        assertEquals("billing", selected.getCategoryId());
        var rated = assertInstanceOf(RatingResult.Answered.class, anger);
        assertEquals("Frustrated", rated.getSelectedLevelId());
    }

    @Test
    void anEquivalentQuestionBuiltLaterReadsTheSameAnswer() {
        var response = answered();
        var rebuilt = Questions.named("department")
            .choice("Which team should handle this?")
            .option("billing", "Payments, invoicing, refunds")
            .option("technical", "Bugs, outages, integrations")
            .option("sales", "Pricing, upgrades, new accounts")
            .build();
        assertNotSame(department, rebuilt);
        ClassificationResult team = response.answer(rebuilt);
        assertEquals(response.answer(department), team);
    }

    @Test
    void nameLookupSupportsPatternMatchingOnTheAnswerKind() {
        var response = answered();

        // tag::by-name[]
        String route = switch (response.answer("department")) {
            case DecisionAnswer.Choice choice
                when choice.getOutcome() instanceof ClassificationResult.Selected selected -> selected.getCategoryId();
            case DecisionAnswer.Choice choice -> "triage-queue";
            case DecisionAnswer.Proposition proposition -> "not a choice";
            case DecisionAnswer.Rating rating -> "not a choice";
        };
        // end::by-name[]

        assertEquals("billing", route);
        if (response.answer("department") instanceof DecisionAnswer.Choice choice) {
            assertEquals(department.getOptions(), choice.getOptions());
            assertEquals(QuestionKind.CHOICE, choice.getKind());
        } else {
            fail("department should be a choice answer");
        }
    }

    @Test
    void lookupsRejectUnknownNamesAndChangedDefinitions() {
        var response = answered();
        var unknown = assertThrows(IllegalArgumentException.class, () -> response.answer("tone"));
        assertTrue(unknown.getMessage().contains("'tone'"));

        var drifted = Questions.named("department")
            .choice("Which team should handle this?")
            .option("billing", "Payments, invoicing, refunds")
            .option("sales", "New business")
            .build();
        var drift = assertThrows(IllegalArgumentException.class, () -> response.answer(drifted));
        assertTrue(drift.getMessage().contains("'department'"));
    }

    @Test
    void aResponseIsCheckedAgainstASpec() {
        var response = answered();
        response.requireMatches(DecisionSpec.of(urgent, department, frustration));

        var mismatch = assertThrows(IllegalArgumentException.class,
            () -> response.requireMatches(DecisionSpec.of(urgent, department)));
        assertTrue(mismatch.getMessage().contains("Extra: 'frustration'"));
    }

    @Test
    void aFailedResponseHoldsAFailureForEveryQuestion() {
        var spec = DecisionSpec.of(urgent, department, frustration);
        var response = DecisionResponse.failed(spec, FailureReason.UNAVAILABLE);

        assertEquals(FailureReason.UNAVAILABLE, response.getRequestFailure());
        assertEquals(List.of("is_urgent", "department", "frustration"),
            response.getAnswers().stream().map(DecisionAnswer::getName).toList());
        assertEquals(new ClassificationResult.Failure(FailureReason.UNAVAILABLE), response.answer(department));
    }
}
