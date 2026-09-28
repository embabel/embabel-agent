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
package com.embabel.agent.api.decision.example;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.support.NoOpDecisionService;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A grounding example shaped like a proposition store: it asks whether a source excerpt supports a
 * claim, records the evidence and lets an application policy decide what to persist.
 */
class DiceGroundingExampleTest {

    private static final ModelProvenance MODEL = new ModelProvenance("grounding-model", "stub", "2026-09", "req-17");

    private static final GroundingFacts FACTS = new GroundingFacts(
        "The Riverside branch opens at 8am on Saturdays.",
        "Saturday hours at Riverside: 8:00 to 13:00.",
        "doc:branch-hours",
        "rev-42");

    // tag::grounding[]
    record GroundingFacts(String claimText, String sourceExcerpt, String sourceId, String sourceRevision) {
    }

    // Only these fields enter the model input, in this order. Source ids and revisions stay out.
    private static final List<Map.Entry<String, Function<GroundingFacts, String>>> INPUT_FIELDS = List.of(
        Map.entry("Claim", GroundingFacts::claimText),
        Map.entry("Source excerpt", GroundingFacts::sourceExcerpt));

    static String render(GroundingFacts facts) {
        return INPUT_FIELDS.stream()
            .map(field -> field.getKey() + ": " + field.getValue().apply(facts))
            .collect(Collectors.joining("\n"));
    }

    static final PropositionQuestionSpec SUPPORTED =
        Questions.named("supported").proposition("Does the source excerpt support the claim?").build();

    static final DecisionSpec GROUNDING = DecisionSpec.of(SUPPORTED);

    static final String POLICY_VERSION = "grounding-policy-3";

    enum Disposition { SUPPORTED, NOT_SUPPORTED, STALE_SOURCE, INCONCLUSIVE, FAILED }

    record EvidenceRecord(
        String correlationId,
        String propositionId,
        String inputDigest,
        String sourceLocator,
        String sourceRevision,
        ModelProvenance model,
        Double pTrue,
        String policyVersion,
        Disposition proposedDisposition) {
    }

    // The policy reads the answer kind and its own source rule. The probability is recorded and not read.
    static Disposition decide(PropositionResult outcome, GroundingFacts facts, String currentSourceRevision) {
        boolean sourceCurrent = facts.sourceRevision().equals(currentSourceRevision);
        return switch (outcome) {
            case PropositionResult.Answered answered when !sourceCurrent -> Disposition.STALE_SOURCE;
            case PropositionResult.Answered answered ->
                answered.getAnswer() ? Disposition.SUPPORTED : Disposition.NOT_SUPPORTED;
            case PropositionResult.Inconclusive inconclusive -> Disposition.INCONCLUSIVE;
            case PropositionResult.Failure failure -> Disposition.FAILED;
        };
    }

    final List<EvidenceRecord> evidenceLog = new ArrayList<>();

    final List<String> groundedPropositions = new ArrayList<>();

    EvidenceRecord ground(Ai ai, String correlationId, String propositionId, GroundingFacts facts,
                          String currentSourceRevision) {
        String input = render(facts);
        DecisionService grounding = ai.decisions().byRole("dice-grounding");
        DecisionResponse response = grounding.ask(input, GROUNDING);
        PropositionResult outcome = response.answer(SUPPORTED);

        Disposition disposition = decide(outcome, facts, currentSourceRevision);
        EvidenceRecord evidence = new EvidenceRecord(
            correlationId,
            propositionId,
            sha256(input),
            facts.sourceId(),
            facts.sourceRevision(),
            switch (outcome) {
                case PropositionResult.Answered answered -> answered.getProvenance();
                case PropositionResult.Inconclusive inconclusive -> inconclusive.getProvenance();
                case PropositionResult.Failure failure -> null;
            },
            outcome instanceof PropositionResult.Answered answered ? answered.getPTrue() : null,
            POLICY_VERSION,
            disposition);
        evidenceLog.add(evidence);
        // The application writes the grounding link. The decision service writes nothing.
        if (disposition == Disposition.SUPPORTED) {
            groundedPropositions.add(propositionId);
        }
        return evidence;
    }
    // end::grounding[]

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", missing);
        }
    }

    private static Ai aiWith(DecisionService service) {
        DecisionServiceRegistry registry = DecisionServiceRegistry.builder()
            .register("grounding", service)
            .decisionRole("dice-grounding", "grounding")
            .build();
        return ExampleOperations.withRegistry(registry).ai();
    }

    private static StubDecisionService stubAnswering(PropositionResult outcome) {
        return StubDecisionService.builder("grounding").proposition("supported", outcome).build();
    }

    @Test
    void aSupportedClaimIsRecordedAndGroundedWithMissingProbabilityKeptNull() {
        StubDecisionService stub = stubAnswering(new PropositionResult.Answered(true, MODEL));

        EvidenceRecord evidence = ground(aiWith(stub), "corr-1", "prop-7", FACTS, "rev-42");

        String expectedInput = "Claim: The Riverside branch opens at 8am on Saturdays.\n"
            + "Source excerpt: Saturday hours at Riverside: 8:00 to 13:00.";
        assertEquals("corr-1", evidence.correlationId());
        assertEquals("prop-7", evidence.propositionId());
        assertEquals(sha256(expectedInput), evidence.inputDigest());
        assertEquals(64, evidence.inputDigest().length());
        assertEquals("doc:branch-hours", evidence.sourceLocator());
        assertEquals("rev-42", evidence.sourceRevision());
        assertEquals(MODEL, evidence.model());
        assertNull(evidence.pTrue());
        assertEquals("grounding-policy-3", evidence.policyVersion());
        assertEquals(Disposition.SUPPORTED, evidence.proposedDisposition());
        assertEquals(List.of(evidence), evidenceLog);
        assertEquals(List.of("prop-7"), groundedPropositions);
        assertEquals(List.of("askNative"), stub.calls());
    }

    @Test
    void theInputHoldsOnlyTheAllowlistedFields() {
        String input = render(FACTS);

        assertFalse(input.contains("doc:branch-hours"));
        assertFalse(input.contains("rev-42"));
    }

    @Test
    void aHighProbabilityAnswerOnAStaleSourceIsNotGrounded() {
        StubDecisionService stub = stubAnswering(new PropositionResult.Answered(true, MODEL, 0.99));

        EvidenceRecord evidence = ground(aiWith(stub), "corr-2", "prop-7", FACTS, "rev-43");

        assertEquals(Disposition.STALE_SOURCE, evidence.proposedDisposition());
        assertEquals(0.99, evidence.pTrue());
        assertEquals(List.of(evidence), evidenceLog);
        assertEquals(List.of(), groundedPropositions);
    }

    @Test
    void anUnsupportedClaimIsRecordedAndNotGrounded() {
        StubDecisionService stub = stubAnswering(new PropositionResult.Answered(false, MODEL, 0.97));

        EvidenceRecord evidence = ground(aiWith(stub), "corr-3", "prop-8", FACTS, "rev-42");

        assertEquals(Disposition.NOT_SUPPORTED, evidence.proposedDisposition());
        assertEquals(0.97, evidence.pTrue());
        assertEquals(List.of(), groundedPropositions);
    }

    @Test
    void anInconclusiveAnswerIsRecordedAsInconclusive() {
        StubDecisionService stub = stubAnswering(new PropositionResult.Inconclusive(MODEL));

        EvidenceRecord evidence = ground(aiWith(stub), "corr-4", "prop-9", FACTS, "rev-42");

        assertEquals(Disposition.INCONCLUSIVE, evidence.proposedDisposition());
        assertEquals(MODEL, evidence.model());
        assertNull(evidence.pTrue());
        assertEquals(List.of(evidence), evidenceLog);
        assertEquals(List.of(), groundedPropositions);
    }

    @Test
    void aFailureIsRecordedAsAFailure() {
        EvidenceRecord evidence = ground(aiWith(new NoOpDecisionService("grounding")), "corr-5", "prop-9", FACTS, "rev-42");

        assertEquals(Disposition.FAILED, evidence.proposedDisposition());
        assertNull(evidence.model());
        assertNull(evidence.pTrue());
        assertEquals(List.of(evidence), evidenceLog);
        assertEquals(List.of(), groundedPropositions);
    }
}
