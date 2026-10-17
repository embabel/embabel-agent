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

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.classification.MappedClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.annotated.AnnotatedDecisions;
import com.embabel.common.ai.decision.annotated.Classification;
import com.embabel.common.ai.decision.annotated.Described;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Routes a support ticket to a team with a classification read from an annotated enum.
 */
class TicketClassificationAnnotatedExampleTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("stub-model", "stub");

    // tag::annotated-classification-enum[]
    @Classification(asking = "Which team should handle this ticket?")
    enum Department {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
        @Described("Accounts, sign-in, permissions")
        ACCOUNT,
    }
    // end::annotated-classification-enum[]

    // tag::annotated-classification-route[]
    record TicketRouter(ClassificationService classifier) {
        Optional<Department> route(String ticket) {
            var ticketClass = AnnotatedDecisions.classification(Department.class);
            ClassificationResult result = classifier.classify(ticket, ticketClass.spec());
            if (ticketClass.map(result) instanceof MappedClassificationResult.Selected<Department> selected) {
                return Optional.of(selected.getValue());
            }
            // No match, inconclusive and failure all go to manual triage.
            return Optional.empty();
        }
    }
    // end::annotated-classification-route[]

    @Test
    void routesASelectionToItsTeam() {
        var router = new TicketRouter(answering(new ClassificationResult.Selected("TECHNICAL", PROVENANCE)));

        assertEquals(Optional.of(Department.TECHNICAL), router.route("The export API returns 500 for everyone."));
    }

    @Test
    void sendsANonSelectionToManualTriage() {
        var router = new TicketRouter(answering(new ClassificationResult.NoMatch(PROVENANCE)));

        assertEquals(Optional.empty(), router.route("Do you have a phone number?"));
    }

    /**
     * Builds a classifier that returns the same result for every request.
     *
     * @param result the result to return
     * @return the classifier
     */
    private static ClassificationService answering(ClassificationResult result) {
        return new ClassificationService() {
            @Override
            public String getName() {
                return "stub-model";
            }

            @Override
            public String getProvider() {
                return "stub";
            }

            @Override
            public ClassificationResult classify(ClassificationRequest request) {
                return request.getSpec().validate(result);
            }
        };
    }
}
