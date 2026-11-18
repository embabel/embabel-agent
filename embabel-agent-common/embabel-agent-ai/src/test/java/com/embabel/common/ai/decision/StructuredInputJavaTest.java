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
package com.embabel.common.ai.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.support.StubDecisionService;

import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class StructuredInputJavaTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("stub-model", "stub");

    private final DecisionService service = StubDecisionService.builder("companions-stub")
            .proposition("is_companion", new PropositionResult.Answered(true, PROVENANCE))
            .build();

    @Test
    void askTakesAStructuredState() {
        // tag::structured[]
        var mapper = JsonMapper.builder().build();
        var state = mapper.createObjectNode();
        state.putObject("intent").put("kind", "COMPANIONS").put("subject", "Tomato");
        state.put("rule", "A plant is a companion only when a fact declares it.");
        state.putArray("facts").addObject()
                .put("subject", "Tomato")
                .put("predicate", "companionOf")
                .put("object", "Basil");

        var spec = DecisionSpec.builder()
                .proposition("is_companion", question -> question
                        .asking("Does `facts` declare Basil a companion of `intent.subject`?"))
                .build();

        var response = service.ask(DecisionRequest.of(state, spec));
        // end::structured[]

        var question = (PropositionQuestionSpec) spec.question("is_companion");
        assertEquals(new PropositionResult.Answered(true, PROVENANCE), response.answer(question));
    }

    @Test
    void aRequestKeepsTheStateAndRendersItAsTheInput() {
        var state = JsonMapper.builder().build().readTree("{\"rule\":\"r\",\"facts\":[1,2]}");
        var spec = DecisionSpec.builder()
                .proposition("is_companion", question -> question.asking("Is it?"))
                .build();

        var request = DecisionRequest.of(state, spec);

        assertEquals(state, request.getState());
        assertEquals("{\"rule\":\"r\",\"facts\":[1,2]}", request.getInput());
        assertNull(DecisionRequest.of("text", spec).getState());
    }

    @Test
    void classifyTakesAStructuredState() {
        var billing = new ClassificationResult.Selected("billing", PROVENANCE);
        var classifier = StubDecisionService.builder("triage-stub")
                .classifying(Set.of("billing", "technical"), billing)
                .build();

        // tag::classify[]
        var ticket = JsonMapper.builder().build().createObjectNode();
        ticket.putObject("ticket").put("subject", "Refund").put("body", "I was charged twice.");
        ticket.put("plan", "pro");

        var departments = ClassificationSpec.builder()
                .asking("Which team should handle `ticket.subject`?")
                .category("billing", "Payments, invoicing, refunds")
                .category("technical", "Bugs, outages, integrations")
                .build();

        var result = classifier.classify(ticket, departments);
        // end::classify[]

        assertEquals(billing, result);
    }
}
