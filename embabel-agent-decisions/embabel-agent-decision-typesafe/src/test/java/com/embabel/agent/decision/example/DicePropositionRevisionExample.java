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
package com.embabel.agent.decision.example;

// tag::dice-consumer[]
import com.embabel.agent.decision.ChoiceKey;
import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionOption;
import com.embabel.agent.decision.DecisionOutcome;
import com.embabel.agent.decision.DecisionProvenance;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.KeyOutcome;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shows the boundary Dice needs without coupling the framework to Dice. */
public final class DicePropositionRevisionExample {
    private DicePropositionRevisionExample() {}

    public enum PropositionRelation { IDENTICAL, SIMILAR, UNRELATED, CONTRADICTORY, GENERALIZES }
    public enum RevisionDisposition { REJECT_CANDIDATE, MERGE, ACCEPT_CANDIDATE, REVIEW_CONFLICT, REPLACE_EXISTING }
    public record PropositionState(String id, String text, double confidence, String status,
                                   String sourceProvenance) {}
    public record SourceProvenance(String existing, String candidate) {}
    public record RevisionEvent(RevisionDisposition disposition,
                                String existingPropositionId, String candidatePropositionId,
                                String questionId, String correlationId,
                                PropositionRelation relation, Map<PropositionRelation, Double> distribution,
                                DecisionProvenance decisionProvenance,
                                SourceProvenance sourceProvenance) {}
    public record RevisionResult(RevisionDisposition disposition, RevisionEvent event) {}

    /** Dice owns this policy and can replace it without changing the decision adapter. */
    public static final class RevisionPolicy {
        public RevisionDisposition dispositionFor(PropositionRelation relation) {
            return switch (relation) {
                case IDENTICAL -> RevisionDisposition.REJECT_CANDIDATE;
                case SIMILAR -> RevisionDisposition.MERGE;
                case UNRELATED -> RevisionDisposition.ACCEPT_CANDIDATE;
                case CONTRADICTORY -> RevisionDisposition.REVIEW_CONFLICT;
                case GENERALIZES -> RevisionDisposition.REPLACE_EXISTING;
            };
        }
    }

    public static RevisionResult revise(DecisionModel model, RevisionPolicy policy, String correlationId,
                                        PropositionState existing, PropositionState candidate) {
        DecisionRequest.Builder builder = DecisionRequest.builder()
                .correlationId(correlationId)
                .timeout(Duration.ofSeconds(20))
                .state(Map.of(
                        "existingText", existing.text(),
                        "candidateText", candidate.text()));
        ChoiceKey<PropositionRelation> relation = builder.choice(
                "proposition-relation", "How are these propositions related?",
                List.of(
                        option(PropositionRelation.IDENTICAL), option(PropositionRelation.SIMILAR),
                        option(PropositionRelation.UNRELATED), option(PropositionRelation.CONTRADICTORY),
                        option(PropositionRelation.GENERALIZES)));
        DecisionOutcome outcome = model.ask(builder.build());
        if (outcome instanceof DecisionOutcome.Failure failure) {
            throw new IllegalStateException("Relation decision failed safely: " + failure.getSafeCode());
        }
        DecisionOutcome.Success success = (DecisionOutcome.Success) outcome;
        KeyOutcome<PropositionRelation> answer = success.answer(relation);
        if (answer instanceof KeyOutcome.Failure<PropositionRelation> failure) {
            throw new IllegalStateException("Relation evidence failed safely: " + failure.getSafeCode());
        }
        KeyOutcome.Success<PropositionRelation> evidence = (KeyOutcome.Success<PropositionRelation>) answer;
        RevisionDisposition disposition = policy.dispositionFor(evidence.getValue());
        // The event is evidence for Dice to persist. It does not mutate either proposition.
        RevisionEvent event = new RevisionEvent(
                disposition, existing.id(), candidate.id(), relation.getId(), correlationId,
                evidence.getValue(), evidence.getDistribution(), success.getProvenance(),
                new SourceProvenance(existing.sourceProvenance(), candidate.sourceProvenance()));
        return new RevisionResult(disposition, event);
    }

    private static DecisionOption<PropositionRelation> option(PropositionRelation relation) {
        return DecisionOption.of(relation.name().toLowerCase(), relation, relation.name().toLowerCase());
    }
}
// end::dice-consumer[]
