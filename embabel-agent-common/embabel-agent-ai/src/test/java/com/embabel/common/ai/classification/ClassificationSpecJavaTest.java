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
package com.embabel.common.ai.classification;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionRequest;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;

import org.junit.jupiter.api.Test;

class ClassificationSpecJavaTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("model", "provider");

    // tag::use-on-its-own[]
    record TicketRouter(ClassificationService classifier, ClassificationSpec departments) {
        String route(String ticketText) {
            ClassificationResult team = classifier.classify(ticketText, departments);
            return switch (team) {
                case ClassificationResult.Selected selected -> selected.getCategoryId();
                case ClassificationResult.NoMatch noMatch -> "triage";
                case ClassificationResult.Inconclusive inconclusive -> "triage";
                case ClassificationResult.Failure failure -> "retry-later";
            };
        }
    }

    // end::use-on-its-own[]

    /**
     * Builds a classifier that picks the first category of every request.
     *
     * @return the classifier
     */
    private static ClassificationService firstCategory() {
        return new ClassificationService() {
            public String getName() {
                return "model";
            }

            public String getProvider() {
                return "provider";
            }

            public ClassificationResult classify(ClassificationRequest request) {
                return request.getSpec()
                        .selected(request.getCategories().getFirst().getId(), PROVENANCE);
            }
        };
    }

    @Test
    void defineOnceAndClassify() {
        // tag::define[]
        ClassificationSpec departments = ClassificationSpec.builder()
            .asking("Which team should handle this?")
            .category("billing", "Payments, invoicing, refunds")
            .category("technical", "Bugs, outages, integrations")
            .build();
        // end::define[]

        ClassificationService classifier = firstCategory();
        String ticketText = "My card was charged twice";
        ClassificationRequest request = ClassificationRequest.of(ticketText, departments);
        ClassificationResult team = classifier.classify(request);
        ClassificationResult same = classifier.classify(ticketText, departments);
        assertEquals(team, same);
        assertEquals(new ClassificationResult.Selected("billing", PROVENANCE), team);
        assertEquals("billing", new TicketRouter(classifier, departments).route(ticketText));
    }

    @Test
    void aClassificationIsADecision() {
        ClassificationSpec departments = ClassificationSpec.builder()
            .asking("Which team should handle this?")
            .category("billing", "Payments, invoicing, refunds")
            .build();
        ChoiceQuestionSpec question = departments.getQuestion();
        DecisionSpec asDecision = departments;
        assertEquals(DecisionSpec.of(question), asDecision);

        DecisionRequest request = ClassificationRequest.of("My card was charged twice", departments);
        DecisionResponse response = DecisionResponse.builder(request.getSpec())
            .answer(question, departments.selected("billing", PROVENANCE, 0.9))
            .build();
        ClassificationResult fromResponse = response.answer(departments.getQuestion());
        assertEquals(new ClassificationResult.Selected("billing", PROVENANCE, 0.9), fromResponse);
    }

    @Test
    void wrapAnExistingChoiceQuestion() {
        var question = ChoiceQuestionSpec.class.cast(
                DecisionSpec.builder()
                        .choice("department", q -> q.asking("Which team?").option("billing", "Payments"))
                        .build()
                        .question("department"));
        var spec = ClassificationSpec.of(question);
        assertEquals("department", spec.getQuestion().getName());
        assertEquals("Which team?", spec.getInstructions());
        assertEquals(1, spec.getCategories().size());
    }
}
