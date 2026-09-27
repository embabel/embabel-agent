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

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.DecisionProjection;
import com.embabel.common.ai.decision.DecisionProjectionException;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.RatingScore;
import com.embabel.common.ai.decision.RatingStatistic;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks projection of a decision response into the annotated type the spec was read from.
 */
class AnnotatedProjectionTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("stub-model", "stub", "1", "req-1");

    private static final String INPUT = "The export API returns 500 for every customer since this morning.";

    enum Department {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
    }

    enum Severity {
        LOW,
        HIGH,
        CRITICAL,
    }

    record Triage(
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent,
        @ChoiceQuestion(asking = "Which team should handle this?") Department department,
        @RatingQuestion(asking = "How severe is the impact?") Severity severity) {
    }

    record SourcedTriage(
        String sourceId,
        int revision,
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent) {
    }

    record TriageWithIgnored(
        @JsonIgnore String internalNote,
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent) {
    }

    record Picked<E extends Enum<E>>(@ChoiceQuestion(asking = "Which team should handle this?") E pick) {
    }

    private static StubDecisionService.Builder answeredStub() {
        return StubDecisionService.builder("triage-stub")
            .proposition("urgent", new PropositionResult.Answered(true, PROVENANCE))
            .choice("department", new ClassificationResult.Selected("TECHNICAL", PROVENANCE))
            .rating("severity", new RatingResult.Answered(PROVENANCE, "CRITICAL"));
    }

    private static DecisionResponse ask(StubDecisionService stub, Class<?> type) {
        return stub.ask(INPUT, AnnotatedDecisions.defaults().of(type).spec());
    }

    @Test
    void answeredValuesProjectIntoTheRecord() {
        AnnotatedDecision<Triage> triage = AnnotatedDecisions.defaults().of(Triage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, triage.spec());

        DecisionProjection<Triage> projection = triage.project(response);

        assertEquals(new Triage(true, Department.TECHNICAL, Severity.CRITICAL), projection.getValue());
    }

    @Test
    void projectionKeepsTheResponseAndItsProvenance() {
        AnnotatedDecision<Triage> triage = AnnotatedDecisions.defaults().of(Triage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, triage.spec());

        DecisionProjection<Triage> projection = triage.project(response);

        assertSame(response, projection.getResponse());
        PropositionQuestionSpec urgent = (PropositionQuestionSpec) triage.spec().question("urgent");
        PropositionResult.Answered answer = (PropositionResult.Answered) projection.getResponse().answer(urgent);
        assertEquals(PROVENANCE, answer.getProvenance());
    }

    @Test
    void inconclusivePropositionFailsProjection() {
        StubDecisionService stub = answeredStub()
            .proposition("urgent", new PropositionResult.Inconclusive(PROVENANCE))
            .build();

        assertNotRepresentable(ask(stub, Triage.class), "urgent", "'urgent' (inconclusive)");
    }

    @Test
    void failedPropositionFailsProjection() {
        StubDecisionService stub = answeredStub()
            .proposition("urgent", new PropositionResult.Failure(FailureReason.UNAVAILABLE))
            .build();

        assertNotRepresentable(ask(stub, Triage.class), "urgent", "'urgent' (failure)");
    }

    @Test
    void choiceWithNoMatchFailsProjection() {
        StubDecisionService stub = answeredStub()
            .choice("department", new ClassificationResult.NoMatch(PROVENANCE))
            .build();

        assertNotRepresentable(ask(stub, Triage.class), "department", "'department' (no_match)");
    }

    @Test
    void ratingWithOnlyAScoreFailsProjection() {
        RatingResult scoreOnly = new RatingResult.Answered(
            PROVENANCE, null, List.of(), new RatingScore(1.5, RatingStatistic.EXPECTED_LEVEL_INDEX));
        StubDecisionService stub = answeredStub().rating("severity", scoreOnly).build();

        assertNotRepresentable(ask(stub, Triage.class), "severity", "'severity' (answered without a selected level)");
    }

    @Test
    void responseToAnotherSpecIsRejectedBeforeConversion() {
        AnnotatedDecision<Triage> triage = AnnotatedDecisions.defaults().of(Triage.class);
        DecisionSpec other = DecisionSpec.builder()
            .proposition("urgent", question -> question.asking("Is the customer angry?"))
            .choice("department", question -> question
                .asking("Which team should handle this?")
                .option("BILLING", "Payments, invoicing, refunds")
                .option("TECHNICAL", "Bugs, outages, integrations"))
            .rating("severity", question -> question
                .asking("How severe is the impact?")
                .level("LOW").level("HIGH").level("CRITICAL"))
            .build();
        DecisionResponse response = answeredStub().build().ask(INPUT, other);

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class, () -> triage.project(response));

        assertEquals(
            "Response answers spec " + other.getDefinitionId() + " but Triage reads spec "
                + triage.spec().getDefinitionId() + ". Ask with AnnotatedDecision.spec() for this type and mapper.",
            e.getMessage());
        assertEquals(List.of(), e.getQuestions());
        assertThrows(DecisionProjectionException.class,
            () -> triage.project(response, Map.of()));
    }

    @Test
    void otherPropertiesFillTheNonQuestionProperties() {
        AnnotatedDecision<SourcedTriage> sourced = AnnotatedDecisions.defaults().of(SourcedTriage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, sourced.spec());

        SourcedTriage value = sourced.project(response, Map.of("sourceId", "ticket-42", "revision", 3));

        assertEquals(new SourcedTriage("ticket-42", 3, true), value);
    }

    @Test
    void missingOtherPropertiesAreRejected() {
        AnnotatedDecision<SourcedTriage> sourced = AnnotatedDecisions.defaults().of(SourcedTriage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, sourced.spec());

        DecisionProjectionException withoutMap =
            assertThrows(DecisionProjectionException.class, () -> sourced.project(response));
        DecisionProjectionException partial = assertThrows(DecisionProjectionException.class,
            () -> sourced.project(response, Map.of("sourceId", "ticket-42")));

        assertEquals("SourcedTriage needs values for [sourceId, revision]. Pass them in otherProperties.",
            withoutMap.getMessage());
        assertEquals("SourcedTriage needs values for [revision]. Pass them in otherProperties.",
            partial.getMessage());
        assertEquals(List.of(), partial.getQuestions());
    }

    @Test
    void unknownOtherPropertiesAreRejected() {
        AnnotatedDecision<SourcedTriage> sourced = AnnotatedDecisions.defaults().of(SourcedTriage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, sourced.spec());

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class,
            () -> sourced.project(response, Map.of("sourceId", "ticket-42", "revision", 3, "source_id", "x")));

        assertEquals("SourcedTriage has no property [source_id] to set from otherProperties. "
            + "Use only the keys [sourceId, revision].", e.getMessage());
    }

    @Test
    void questionNamedOtherPropertiesAreRejected() {
        AnnotatedDecision<SourcedTriage> sourced = AnnotatedDecisions.defaults().of(SourcedTriage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, sourced.spec());

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class,
            () -> sourced.project(response, Map.of("sourceId", "ticket-42", "revision", 3, "urgent", false)));

        assertEquals("SourcedTriage takes [urgent] from the response answers. "
            + "Remove them from otherProperties.", e.getMessage());
    }

    @Test
    void everyOtherPropertyProblemIsReportedAtOnce() {
        AnnotatedDecision<SourcedTriage> sourced = AnnotatedDecisions.defaults().of(SourcedTriage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, sourced.spec());

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class,
            () -> sourced.project(response, Map.of("urgent", false, "extra", 1)));

        assertEquals("SourcedTriage needs values for [sourceId, revision]. Pass them in otherProperties.\n"
            + "SourcedTriage has no property [extra] to set from otherProperties. Use only the keys [sourceId, revision].\n"
            + "SourcedTriage takes [urgent] from the response answers. Remove them from otherProperties.",
            e.getMessage());
    }

    @Test
    void nullOtherPropertyValueCountsAsSupplied() {
        AnnotatedDecision<SourcedTriage> sourced = AnnotatedDecisions.defaults().of(SourcedTriage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, sourced.spec());
        Map<String, Object> others = new HashMap<>();
        others.put("sourceId", null);
        others.put("revision", 1);

        SourcedTriage value = sourced.project(response, others);

        assertNull(value.sourceId());
    }

    @Test
    void ignoredPropertiesAreNotOtherPropertyKeys() {
        AnnotatedDecision<TriageWithIgnored> decision = AnnotatedDecisions.defaults().of(TriageWithIgnored.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, decision.spec());

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class,
            () -> decision.project(response, Map.of("internalNote", "x")));

        assertEquals("TriageWithIgnored has no property [internalNote] to set from otherProperties. "
            + "Use only the keys [].", e.getMessage());
    }

    @Test
    void ignoredRecordComponentProjectsOnlyWithoutMissingCreatorPropertyChecks() {
        // Jackson keeps an ignored record component as a creator parameter. The default mapper fails
        // on a missing creator property, so it reports the ignored component as missing.
        AnnotatedDecision<TriageWithIgnored> strict = AnnotatedDecisions.defaults().of(TriageWithIgnored.class);
        DecisionResponse strictResponse = answeredStub().build().ask(INPUT, strict.spec());
        DecisionProjectionException e =
            assertThrows(DecisionProjectionException.class, () -> strict.project(strictResponse));
        assertTrue(e.getCause().getMessage().startsWith("Missing creator property 'internalNote'"),
            e.getCause().getMessage());

        AnnotatedDecision<TriageWithIgnored> lenient =
            AnnotatedDecisions.using(JsonMapper.builder().build()).of(TriageWithIgnored.class);
        DecisionResponse lenientResponse = answeredStub().build().ask(INPUT, lenient.spec());
        assertEquals(new TriageWithIgnored(null, true), lenient.project(lenientResponse).getValue());
    }

    @Test
    void valuesThatDoNotFitTheTypeKeepTheJacksonCause() {
        AnnotatedDecision<SourcedTriage> sourced = AnnotatedDecisions.defaults().of(SourcedTriage.class);
        DecisionResponse response = answeredStub().build().ask(INPUT, sourced.spec());

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class,
            () -> sourced.project(response, Map.of("sourceId", "ticket-42", "revision", "not a number")));

        assertEquals("Cannot map the answered values and otherProperties to " + SourcedTriage.class.getName(),
            e.getMessage());
        assertTrue(e.getCause() instanceof tools.jackson.core.JacksonException, String.valueOf(e.getCause()));
    }

    @Test
    void genericRecordIsRejectedWhenRead() {
        AnnotatedDecisionException e = assertThrows(AnnotatedDecisionException.class,
            () -> AnnotatedDecisions.defaults().of(Picked.class));

        assertSame(Picked.class, e.type());
        assertEquals(
            List.of("Picked.pick: @ChoiceQuestion needs a concrete enum type, found type variable E, which Jackson "
                + "reads as java.lang.Enum. Declare the property with a concrete enum type."),
            e.problems());
    }

    private static void assertNotRepresentable(DecisionResponse response, String question, String status) {
        AnnotatedDecision<Triage> triage = AnnotatedDecisions.defaults().of(Triage.class);

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class, () -> triage.project(response));

        assertEquals(List.of(question), e.getQuestions());
        assertTrue(e.getMessage().contains(status), e.getMessage());
    }
}
