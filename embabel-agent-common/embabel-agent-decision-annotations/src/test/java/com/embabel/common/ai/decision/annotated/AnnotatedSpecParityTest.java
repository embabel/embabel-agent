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
package com.embabel.common.ai.decision.annotated;

import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.RatingLevel;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that a spec read from an annotated record equals the spec declared with the builder.
 */
class AnnotatedSpecParityTest {

    enum Department {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
    }

    enum Severity {
        LOW,
        @Described("Work is blocked for one customer")
        HIGH,
        @Described("Work is blocked for many customers")
        CRITICAL,
    }

    record Triage(
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent,
        @ChoiceQuestion(asking = "Which team should handle this?") Department department,
        @RatingQuestion(asking = "How severe is the impact?") Severity severity) {
    }

    @JsonPropertyOrder({"severity", "urgent", "department"})
    record ReorderedTriage(
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent,
        @ChoiceQuestion(asking = "Which team should handle this?") Department department,
        @RatingQuestion(asking = "How severe is the impact?") Severity severity) {
    }

    record TriageWithSource(
        String sourceId,
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent) {
    }

    private static DecisionSpec builderTriage() {
        return DecisionSpec.builder()
            .proposition("urgent", question -> question
                .asking("Does this ticket convey urgency?"))
            .choice("department", question -> question
                .asking("Which team should handle this?")
                .option("BILLING", "Payments, invoicing, refunds")
                .option("TECHNICAL", "Bugs, outages, integrations"))
            .rating("severity", question -> question
                .asking("How severe is the impact?")
                .level("LOW")
                .level("HIGH", "Work is blocked for one customer")
                .level("CRITICAL", "Work is blocked for many customers"))
            .build();
    }

    @Test
    void recordSpecEqualsTheBuilderSpec() {
        DecisionSpec spec = AnnotatedDecisions.defaults().of(Triage.class).spec();

        assertEquals(builderTriage(), spec);
        assertEquals(builderTriage().getDefinitionId(), spec.getDefinitionId());
    }

    @Test
    void ratingLevelsFollowEnumDeclarationOrderLowestFirst() {
        RatingQuestionSpec severity = (RatingQuestionSpec) AnnotatedDecisions.defaults().of(Triage.class)
            .spec().question("severity");

        assertEquals(
            List.of(
                new RatingLevel("LOW", "LOW"),
                new RatingLevel("HIGH", "Work is blocked for one customer"),
                new RatingLevel("CRITICAL", "Work is blocked for many customers")),
            severity.getLevels());
    }

    @Test
    void questionNamesMapJavaMembersToQuestionsInSpecOrder() {
        AnnotatedDecision<Triage> decision = AnnotatedDecisions.defaults().of(Triage.class);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("urgent", "urgent");
        expected.put("department", "department");
        expected.put("severity", "severity");
        assertEquals(expected, decision.questionNames());
        assertEquals(List.copyOf(expected.values()), List.copyOf(decision.questionNames().values()));
        assertSame(Triage.class, decision.type());
        assertThrows(UnsupportedOperationException.class, () -> decision.questionNames().put("x", "y"));
    }

    @Test
    void jsonPropertyOrderChangesQuestionOrderAndSpecId() {
        DecisionSpec reordered = AnnotatedDecisions.defaults().of(ReorderedTriage.class).spec();
        DecisionSpec original = AnnotatedDecisions.defaults().of(Triage.class).spec();

        assertEquals(
            List.of("severity", "urgent", "department"),
            reordered.getQuestions().stream().map(question -> question.getName()).toList());
        assertNotEquals(original.getDefinitionId(), reordered.getDefinitionId());
        assertNotEquals(original, reordered);
    }

    @Test
    void propertiesWithoutQuestionAnnotationsAreNotQuestions() {
        AnnotatedDecision<TriageWithSource> decision = AnnotatedDecisions.defaults().of(TriageWithSource.class);

        assertEquals(List.of("urgent"), decision.spec().getQuestions().stream().map(question -> question.getName()).toList());
        assertEquals(Map.of("urgent", "urgent"), decision.questionNames());
    }
}
