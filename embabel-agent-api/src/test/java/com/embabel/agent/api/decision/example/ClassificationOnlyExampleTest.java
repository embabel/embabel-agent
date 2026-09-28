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
import com.embabel.common.ai.classification.CategoryMapping;
import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.MappedClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.support.NoOpDecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * An application that only classifies: it registers a classification service under a role and
 * routes tickets with a classification spec.
 */
class ClassificationOnlyExampleTest {

    private static final ModelProvenance MODEL = new ModelProvenance("keyword-model", "example");

    private static final String BILLING_TICKET = "The billing page charged me twice.";

    private static final String TECHNICAL_TICKET = "Technical fault: the export button crashes.";

    private static final String OTHER_TICKET = "Can I change my username?";

    // tag::spec[]
    static final ClassificationSpec DEPARTMENTS = ClassificationSpec.builder()
        .asking("Which team should handle this?")
        .category("billing", "Payments, invoicing, refunds")
        .category("technical", "Bugs, outages, integrations")
        .build();
    // end::spec[]

    // tag::route[]
    static String queueFor(Ai ai, String ticketText) {
        ClassificationService classifier = ai.classifications().byRole("ticket-routing");
        return switch (classifier.classify(ticketText, DEPARTMENTS)) {
            case ClassificationResult.Selected selected -> selected.getCategoryId();
            case ClassificationResult.NoMatch noMatch -> "general";
            case ClassificationResult.Inconclusive inconclusive -> "triage";
            case ClassificationResult.Failure failure -> "triage";
        };
    }
    // end::route[]

    // tag::enum[]
    enum Department { BILLING, TECHNICAL }

    static final CategoryMapping<Department> DEPARTMENT_MAPPING = CategoryMapping.fromEnum(
        Department.class,
        "Which team should handle this?",
        department -> switch (department) {
            case BILLING -> "Payments, invoicing, refunds";
            case TECHNICAL -> "Bugs, outages, integrations";
        });

    static Optional<Department> departmentFor(Ai ai, String ticketText) {
        ClassificationService classifier = ai.classifications().byRole("ticket-routing");
        ClassificationResult result = classifier.classify(ticketText, DEPARTMENT_MAPPING.spec());
        return switch (DEPARTMENT_MAPPING.map(result)) {
            case MappedClassificationResult.Selected<Department> selected -> Optional.of(selected.getValue());
            case ClassificationResult.NoMatch noMatch -> Optional.empty();
            case ClassificationResult.Inconclusive inconclusive -> Optional.empty();
            case ClassificationResult.Failure failure -> Optional.empty();
        };
    }
    // end::enum[]

    private final Ai ai = routingTo(new KeywordClassifier());

    /**
     * Builds an {@code Ai} whose {@code ticket-routing} classification role and classification default
     * are the given service.
     *
     * @param service the service that classifies tickets
     * @return an {@code Ai} from a workflow operation over that registry
     */
    private static Ai routingTo(ClassificationService service) {
        DecisionServiceRegistry registry = DecisionServiceRegistry.builder()
            .register("ticket-classifier", service)
            .classificationDefault("ticket-classifier")
            .classificationRole("ticket-routing", "ticket-classifier")
            .build();
        return ExampleOperations.withRegistry(registry).ai();
    }

    @Test
    void everySelectorReturnsAServiceThatClassifies() {
        // tag::selectors[]
        ClassificationService byRole = ai.classifications().byRole("ticket-routing");
        ClassificationService byDefault = ai.classifications().defaultService();
        ClassificationService byName = ai.classifications().named("ticket-classifier");
        ClassificationService supplied = ai.classifications().using(new KeywordClassifier());
        // end::selectors[]

        for (ClassificationService service : List.of(byRole, byDefault, byName, supplied)) {
            assertEquals("keyword-classifier", service.getName());
            assertEquals(
                new ClassificationResult.Selected("billing", MODEL),
                service.classify(BILLING_TICKET, DEPARTMENTS));
        }
    }

    @Test
    void eachOutcomeRoutesToAQueue() {
        ClassificationService classifier = ai.classifications().byRole("ticket-routing");

        assertEquals("billing", queueFor(ai, BILLING_TICKET));
        assertEquals("technical", queueFor(ai, TECHNICAL_TICKET));

        assertInstanceOf(ClassificationResult.NoMatch.class, classifier.classify(OTHER_TICKET, DEPARTMENTS));
        assertEquals("general", queueFor(ai, OTHER_TICKET));

        assertInstanceOf(ClassificationResult.Inconclusive.class, classifier.classify(" ", DEPARTMENTS));
        assertEquals("triage", queueFor(ai, " "));

        // A decision service answers a classification spec too. This one is switched off.
        Ai disabled = routingTo(new NoOpDecisionService("disabled"));
        assertEquals(
            new ClassificationResult.Failure(FailureReason.UNAVAILABLE),
            disabled.classifications().byRole("ticket-routing").classify(BILLING_TICKET, DEPARTMENTS));
        assertEquals("triage", queueFor(disabled, BILLING_TICKET));
    }

    @Test
    void anEnumMappingReturnsTheSelectedConstant() {
        assertEquals(Optional.of(Department.BILLING), departmentFor(ai, BILLING_TICKET));
        assertEquals(Optional.of(Department.TECHNICAL), departmentFor(ai, TECHNICAL_TICKET));
        assertEquals(Optional.empty(), departmentFor(ai, OTHER_TICKET));
        assertEquals(Optional.empty(), departmentFor(ai, " "));
        assertEquals(Optional.empty(), departmentFor(routingTo(new NoOpDecisionService("disabled")), BILLING_TICKET));
    }

    /**
     * Stands in for a model. It selects the first category whose id appears in the ticket, finds no
     * match when none does, and is inconclusive for blank text.
     */
    static final class KeywordClassifier implements ClassificationService {

        @Override
        public String getName() {
            return "keyword-classifier";
        }

        @Override
        public String getProvider() {
            return "example";
        }

        @Override
        public ClassificationResult classify(ClassificationRequest request) {
            String text = request.getInput().toLowerCase(Locale.ROOT);
            if (text.isBlank()) {
                return new ClassificationResult.Inconclusive(MODEL);
            }
            return request.getCategories().stream()
                .filter(category -> text.contains(category.getId().toLowerCase(Locale.ROOT)))
                .findFirst()
                .<ClassificationResult>map(category -> request.getSpec().selected(category.getId(), MODEL))
                .orElse(new ClassificationResult.NoMatch(MODEL));
        }
    }
}
