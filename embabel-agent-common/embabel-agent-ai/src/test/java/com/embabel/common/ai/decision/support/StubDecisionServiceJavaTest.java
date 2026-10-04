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
package com.embabel.common.ai.decision.support;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;

import org.junit.jupiter.api.Test;

import java.util.List;

class StubDecisionServiceJavaTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("stub-model", "stub");

    @Test
    void stubAnswersAskFromScriptedOutcomes() {
        var spec = DecisionSpec.builder()
                .proposition("is_urgent", question -> question.asking("Does this convey urgency?"))
                .choice("department", question -> question
                        .asking("Which team should handle this?")
                        .option("billing", "Payments, invoicing, refunds")
                        .option("technical", "Bugs, outages, integrations"))
                .build();

        var stub = StubDecisionService.builder("triage-stub")
                .proposition("is_urgent", new PropositionResult.Answered(true, PROVENANCE))
                .choice("department", new ClassificationResult.Selected("technical", PROVENANCE))
                .build();

        var response = stub.ask("The export API returns 500 since this morning.", spec);

        var urgent = (PropositionQuestionSpec) spec.question("is_urgent");
        var department = (ChoiceQuestionSpec) spec.question("department");
        assertEquals(new PropositionResult.Answered(true, PROVENANCE), response.answer(urgent));
        assertEquals(new ClassificationResult.Selected("technical", PROVENANCE), response.answer(department));
        assertEquals(List.of("askNative"), stub.calls());
    }

    @Test
    void perQuestionStubAnswersEachQuestionByName() {
        var spec = DecisionSpec.builder()
                .proposition("is_urgent", question -> question.asking("Does this convey urgency?"))
                .proposition("is_refund", question -> question.asking("Is this a refund request?"))
                .build();

        var stub = StubDecisionService.builder("triage-stub")
                .proposition("is_urgent", new PropositionResult.Answered(true, PROVENANCE))
                .proposition("is_refund", new PropositionResult.Answered(false, PROVENANCE))
                .perQuestion()
                .build();

        var response = stub.ask("The export API returns 500 since this morning.", spec);

        var refund = (PropositionQuestionSpec) spec.question("is_refund");
        assertEquals(new PropositionResult.Answered(false, PROVENANCE), response.answer(refund));
        assertEquals(List.of("assess", "assess"), stub.calls());
    }
}
