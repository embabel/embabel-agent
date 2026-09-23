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

// tag::java-consumer[]
import com.embabel.agent.decision.ChoiceKey;
import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionOption;
import com.embabel.agent.decision.DecisionOutcome;
import com.embabel.agent.decision.DecisionRecordPolicy;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.DecisionProvenance;
import com.embabel.agent.decision.KeyOutcome;
import com.embabel.agent.decision.RatingKey;
import com.embabel.agent.decision.YesNoKey;
import com.embabel.agent.decision.typesafe.TypeSafeDecisionModel;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.ModelSelectionCriteria;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class JevDecisionExample {
    private JevDecisionExample() {}

    public enum Route { ACCEPT, REVIEW, REJECT }
    public enum Urgency { LOW, MEDIUM, HIGH }
    public record Evidence(boolean eligible, Route route, Urgency urgency,
                           Map<Route, Double> routeDistribution, DecisionProvenance provenance) {}

    public static Evidence run(DecisionModel model) {
        return run(model, Duration.ofSeconds(20));
    }

    // tag::java-selection[]
    public static Evidence runDefault(ModelProvider models) {
        return run(models.getDecisionModel(ModelSelectionCriteria.getPlatformDefault()));
    }

    public static Evidence runAuto(ModelProvider models) {
        return run(models.getDecisionModel(ModelSelectionCriteria.getAuto()));
    }

    public static Evidence runNamed(ModelProvider models, String name) {
        return run(models.getDecisionModel(ModelSelectionCriteria.byName(name)));
    }

    public static Evidence runForRole(ModelProvider models, String role) {
        return run(models.getDecisionModel(ModelSelectionCriteria.byRole(role)));
    }

    public static Evidence runFirstAvailable(ModelProvider models, String... names) {
        return run(models.getDecisionModel(ModelSelectionCriteria.firstOf(names)));
    }

    public static Evidence runRandom(ModelProvider models, String... names) {
        return run(models.getDecisionModel(ModelSelectionCriteria.randomOf(names)));
    }

    public static Evidence runPreResolved(ModelProvider models, DecisionModel model) {
        return run(models.getDecisionModel(ModelSelectionCriteria.preResolved(model)));
    }
    // end::java-selection[]

    static Evidence run(DecisionModel model, Duration timeout) {
        DecisionRequest.Builder builder = DecisionRequest.builder()
                .state(Map.of("subject", "synthetic public example"))
                .timeout(timeout);
        YesNoKey eligible = builder.yesNo("eligible", "Is this item eligible?");
        ChoiceKey<Route> route = builder.choice("route", "Which route should the host consider?", List.of(
                DecisionOption.of("accept", Route.ACCEPT, "accept"),
                DecisionOption.of("review", Route.REVIEW, "review"),
                DecisionOption.of("reject", Route.REJECT, "reject")));
        RatingKey<Urgency> urgency = builder.rating("urgency", "How urgent is review?", List.of(
                DecisionOption.of("low", Urgency.LOW, "low"),
                DecisionOption.of("medium", Urgency.MEDIUM, "medium"),
                DecisionOption.of("high", Urgency.HIGH, "high")));

        DecisionOutcome.Success success = switch (model.ask(builder.build())) {
            case DecisionOutcome.Success completed -> completed;
            case DecisionOutcome.Failure failure ->
                    throw new IllegalStateException("Decision failed safely: " + failure.getSafeCode());
        };
        var yes = requireEvidence(success.answer(eligible), "Eligibility");
        var selectedRoute = requireEvidence(success.answer(route), "Route");
        var selectedUrgency = requireEvidence(success.answer(urgency), "Urgency");
        return new Evidence(yes.getValue(), selectedRoute.getValue(), selectedUrgency.getValue(),
                selectedRoute.getDistribution(), success.getProvenance());
    }
    public static void main(String[] args) {
        String key = requiredEnvironment("TYPESAFE_API_KEY");
        String requestedModel = requiredEnvironment("TYPESAFE_MODEL");
        try (var provider = TypeSafeDecisionModel.create(() -> key, requestedModel);
             var model = provider.withDefaults(Duration.ofSeconds(20), DecisionRecordPolicy.metadata())) {
            Evidence evidence = run(model);
            System.out.println("Decision completed with " + evidence.provenance().getEvidenceKind()
                    + " evidence from " + evidence.provenance().getResolvedModel());
        }
    }

    private static <T> KeyOutcome.Success<T> requireEvidence(KeyOutcome<T> outcome, String label) {
        return switch (outcome) {
            case KeyOutcome.Success<T> success -> success;
            case KeyOutcome.Failure<T> failure ->
                    throw new IllegalStateException(label + " evidence failed safely: " + failure.getSafeCode());
        };
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required; no network request was made");
        }
        return value;
    }
}
// end::java-consumer[]
