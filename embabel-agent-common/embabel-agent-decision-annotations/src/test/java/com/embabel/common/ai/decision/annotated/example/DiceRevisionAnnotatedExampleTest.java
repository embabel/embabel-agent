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
package com.embabel.common.ai.decision.annotated.example;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.DecisionAnswer;
import com.embabel.common.ai.decision.DecisionProjectionException;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Question;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.annotated.AnnotatedDecision;
import com.embabel.common.ai.decision.annotated.AnnotatedDecisions;
import com.embabel.common.ai.decision.annotated.ChoiceQuestion;
import com.embabel.common.ai.decision.annotated.Described;
import com.embabel.common.ai.decision.annotated.PropositionQuestion;
import com.embabel.common.ai.decision.annotated.RatingQuestion;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.fasterxml.jackson.annotation.JsonCreator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reviews a stored proposition against a new source revision with an annotated entity shaped like
 * a Dice proposition review. The entity carries identity fields the model does not answer, which
 * projection takes from otherProperties. An application policy then reads the projected entity
 * together with the response provenance. The test uses no Dice code or storage.
 */
class DiceRevisionAnnotatedExampleTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("review-model", "stub", "2026-09", "req-7");

    private static final String EVIDENCE = "Revision 12 of the source moves the launch from March to June.";

    // tag::annotated-entity[]
    enum RevisionAction {
        @Described("The proposition still holds for the new revision")
        KEEP,
        @Described("The proposition needs new wording for the new revision")
        REVISE,
        @Described("The new revision contradicts the proposition")
        RETIRE,
    }

    enum Confidence {
        LOW,
        MEDIUM,
        HIGH,
    }

    static final class PropositionReview {

        private final String propositionId;

        private final long sourceRevision;

        private final boolean supported;

        private final RevisionAction action;

        private final Confidence confidence;

        @JsonCreator
        PropositionReview(
            String propositionId,
            long sourceRevision,
            @PropositionQuestion(asking = "Does the new source revision support the proposition?")
            boolean supported,
            @ChoiceQuestion(asking = "What should happen to the stored proposition?")
            RevisionAction action,
            @RatingQuestion(asking = "How confident is the review?")
            Confidence confidence) {
            this.propositionId = propositionId;
            this.sourceRevision = sourceRevision;
            this.supported = supported;
            this.action = action;
            this.confidence = confidence;
        }

        public String getPropositionId() {
            return propositionId;
        }

        public long getSourceRevision() {
            return sourceRevision;
        }

        public boolean isSupported() {
            return supported;
        }

        public RevisionAction getAction() {
            return action;
        }

        public Confidence getConfidence() {
            return confidence;
        }
    }
    // end::annotated-entity[]

    /** What the application does with one review, and the model that answered it. */
    record Disposition(String propositionId, long sourceRevision, String outcome, String answeredBy) {
    }

    // tag::annotated-policy[]
    static final class RevisionPolicy {

        private final AnnotatedDecision<PropositionReview> review =
            AnnotatedDecisions.defaults().of(PropositionReview.class);

        Disposition dispose(DecisionResponse response, String propositionId, long sourceRevision) {
            PropositionQuestionSpec supported = (PropositionQuestionSpec) review.spec().question("supported");
            PropositionResult support = response.answer(supported);
            try {
                PropositionReview entity = review.project(
                    response, Map.of("propositionId", propositionId, "sourceRevision", sourceRevision));
                String outcome = entity.getConfidence() == Confidence.LOW ? "HUMAN_REVIEW" : entity.getAction().name();
                String model = ((PropositionResult.Answered) support).getProvenance().getModelName();
                return new Disposition(entity.getPropositionId(), entity.getSourceRevision(), outcome, model);
            } catch (DecisionProjectionException e) {
                // An answer without a value leaves the stored proposition unchanged.
                return new Disposition(propositionId, sourceRevision, "DEFERRED " + statuses(response, e), null);
            }
        }

        private static List<String> statuses(DecisionResponse response, DecisionProjectionException e) {
            return e.getQuestions().stream().map(name -> name + "=" + status(response.answer(name))).toList();
        }

        private static String status(DecisionAnswer answer) {
            if (answer instanceof DecisionAnswer.Proposition proposition) {
                return proposition.getOutcome().getClass().getSimpleName();
            }
            if (answer instanceof DecisionAnswer.Choice choice) {
                return choice.getOutcome().getClass().getSimpleName();
            }
            return ((DecisionAnswer.Rating) answer).getOutcome().getClass().getSimpleName();
        }
    }
    // end::annotated-policy[]

    private static StubDecisionService.Builder reviewStub() {
        return StubDecisionService.builder("review-stub")
            .proposition("supported", new PropositionResult.Answered(false, PROVENANCE))
            .choice("action", new ClassificationResult.Selected("REVISE", PROVENANCE))
            .rating("confidence", new RatingResult.Answered(PROVENANCE, "HIGH"));
    }

    @Test
    void entityReadsIdentityFromOtherPropertiesAndAnswersFromTheResponse() {
        AnnotatedDecision<PropositionReview> review = AnnotatedDecisions.defaults().of(PropositionReview.class);
        DecisionResponse response = reviewStub().build().ask(EVIDENCE, review.spec());

        PropositionReview entity =
            review.project(response, Map.of("propositionId", "prop-381", "sourceRevision", 12L));

        assertEquals(List.of("supported", "action", "confidence"),
            review.spec().getQuestions().stream().map(Question::getName).toList());
        assertEquals("prop-381", entity.getPropositionId());
        assertEquals(12L, entity.getSourceRevision());
        assertEquals(false, entity.isSupported());
        assertEquals(RevisionAction.REVISE, entity.getAction());
        assertEquals(Confidence.HIGH, entity.getConfidence());
    }

    @Test
    void policyReadsTheEntityAndTheResponseProvenance() {
        RevisionPolicy policy = new RevisionPolicy();
        DecisionResponse response = reviewStub().build().ask(EVIDENCE, policy.review.spec());

        Disposition disposition = policy.dispose(response, "prop-381", 12L);

        assertEquals(new Disposition("prop-381", 12L, "REVISE", "review-model"), disposition);
    }

    @Test
    void lowConfidenceRoutesToHumanReview() {
        RevisionPolicy policy = new RevisionPolicy();
        DecisionResponse response = reviewStub()
            .rating("confidence", new RatingResult.Answered(PROVENANCE, "LOW"))
            .build()
            .ask(EVIDENCE, policy.review.spec());

        assertEquals("HUMAN_REVIEW", policy.dispose(response, "prop-381", 12L).outcome());
    }

    @Test
    void inconclusiveAnswerFailsProjectionAndThePolicyDefers() {
        AnnotatedDecision<PropositionReview> review = AnnotatedDecisions.defaults().of(PropositionReview.class);
        DecisionResponse response = reviewStub()
            .proposition("supported", new PropositionResult.Inconclusive(PROVENANCE))
            .build()
            .ask(EVIDENCE, review.spec());
        Map<String, Object> identity = Map.of("propositionId", "prop-381", "sourceRevision", 12L);

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class,
            () -> review.project(response, identity));
        assertEquals(List.of("supported"), e.getQuestions());

        Disposition disposition = new RevisionPolicy().dispose(response, "prop-381", 12L);
        assertEquals(new Disposition("prop-381", 12L, "DEFERRED [supported=Inconclusive]", null), disposition);
        PropositionQuestionSpec supported = (PropositionQuestionSpec) review.spec().question("supported");
        assertEquals(new PropositionResult.Inconclusive(PROVENANCE), response.answer(supported));
    }

    @Test
    void identityFieldsAreRequired() {
        AnnotatedDecision<PropositionReview> review = AnnotatedDecisions.defaults().of(PropositionReview.class);
        DecisionResponse response = reviewStub().build().ask(EVIDENCE, review.spec());
        Map<String, Object> identity = Map.of("propositionId", "prop-381");

        DecisionProjectionException e = assertThrows(DecisionProjectionException.class,
            () -> review.project(response, identity));

        assertEquals("PropositionReview needs values for [sourceRevision]. Pass them in otherProperties.",
            e.getMessage());
    }
}
