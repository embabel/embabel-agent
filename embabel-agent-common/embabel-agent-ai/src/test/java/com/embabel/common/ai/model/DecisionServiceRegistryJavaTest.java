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
package com.embabel.common.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

class DecisionServiceRegistryJavaTest {

    private static DecisionService decision(String name) {
        return new DecisionService() {
            public String getName() {
                return name;
            }

            public String getProvider() {
                return "TypeSafe";
            }

            public ClassificationResult classify(ClassificationRequest request) {
                throw new AssertionError("not called");
            }

            public PropositionResult assess(PropositionRequest request) {
                throw new AssertionError("not called");
            }
        };
    }

    @Test
    void builderChainAndRoleLookup() {
        DecisionService jev = decision("jev-1");
        DecisionService fast = decision("jev-fast-1");
        ObservationRegistry observations = ObservationRegistry.create();
        DecisionServiceRegistry registry =
                DecisionServiceRegistry.builder()
                        .register("jev", jev)
                        .register("jev-fast", fast)
                        .decisionDefault("jev")
                        .decisionRole("support-triage", "jev-fast")
                        .classificationRole("routing", "jev")
                        .defaultCandidate("jev")
                        .observationRegistry(observations)
                        .build();

        ServiceSelector<DecisionService> decisions = registry.decisions();
        DecisionService triage = decisions.byRole("support-triage");
        assertSame(fast, triage);
        assertSame(jev, decisions.defaultService());
        assertSame(jev, decisions.named("jev"));
        ClassificationService routing = registry.classifications().byRole("routing");
        assertSame(jev, routing);
        assertEquals(List.of("jev", "jev-fast"), registry.registrationNames());
        assertEquals(2, registry.listServices().size());
        assertSame(observations, registry.getObservationRegistry());
    }

    @Test
    void selectionFailureExposesReason() {
        DecisionServiceRegistry registry = DecisionServiceRegistry.empty();
        ServiceSelector<DecisionService> decisions = registry.decisions();
        ServiceSelectionException e =
                assertThrows(ServiceSelectionException.class, () -> decisions.byRole("support-triage"));
        assertEquals(ServiceSelectionException.Reason.UNKNOWN_ROLE, e.getReason());
        assertTrue(e.getMessage().contains("embabel.models.decision.roles.support-triage"));
        DecisionService adHoc = decision("per-user");
        assertSame(adHoc, registry.decisions().using(adHoc));
    }

    @Test
    void defaultCandidateIsAValue() {
        assertEquals(
                new DecisionServiceRegistry.DefaultCandidate("typeSafeDecisionService"),
                new DecisionServiceRegistry.DefaultCandidate("typeSafeDecisionService"));
        assertEquals(
                "typeSafeDecisionService",
                new DecisionServiceRegistry.DefaultCandidate("typeSafeDecisionService").getName());
    }
}
