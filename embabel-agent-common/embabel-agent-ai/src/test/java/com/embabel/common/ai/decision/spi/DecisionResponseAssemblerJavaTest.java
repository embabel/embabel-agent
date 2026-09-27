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
package com.embabel.common.ai.decision.spi;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;

import org.junit.jupiter.api.Test;

class DecisionResponseAssemblerJavaTest {

    private static final ModelProvenance JEV = new ModelProvenance("jev-latest", "typesafe");

    private final PropositionQuestionSpec urgent = Questions.named("is_urgent")
        .proposition("Does this convey urgency?")
        .build();

    private final ChoiceQuestionSpec department = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .build();

    private final RatingQuestionSpec frustration = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Frustrated")
        .level("Very angry")
        .build();

    private final DecisionSpec spec = DecisionSpec.of(urgent, department, frustration);

    @Test
    void assemblesTypedOutcomesInSpecOrder() {
        DecisionResponse response = DecisionResponseAssembler.forSpec(spec, "svc")
            .rating("frustration", new RatingResult.Answered(JEV, "Frustrated"))
            .choice("department", new ClassificationResult.Selected("billing", JEV, 0.91))
            .proposition("is_urgent", new PropositionResult.Answered(true, JEV, 0.93))
            .build();

        PropositionResult urgency = response.answer(urgent);
        ClassificationResult team = response.answer(department);
        RatingResult anger = response.answer(frustration);

        assertEquals(new PropositionResult.Answered(true, JEV, 0.93), urgency);
        assertEquals(new ClassificationResult.Selected("billing", JEV, 0.91), team);
        assertEquals(new RatingResult.Answered(JEV, "Frustrated"), anger);
        assertNull(response.getRequestFailure());
        assertEquals("is_urgent", response.getAnswers().get(0).getName());
    }

    @Test
    void reportsAnomaliesThroughTheNestedEnum() {
        DecisionResponseAssembler assembler = DecisionResponseAssembler.forSpec(spec, "svc")
            .proposition("is_urgent", new PropositionResult.Answered(false, JEV))
            .unreadable("department")
            .unreadable("frustration", DecisionResponseAssembler.Anomaly.BAD_DISTRIBUTION)
            .unexpected();
        DecisionResponse response = assembler.build();

        assertEquals(new ClassificationResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(department));
        assertEquals(new RatingResult.Failure(FailureReason.INVALID_RESPONSE), response.answer(frustration));
    }

    @Test
    void unsafeEnvelopeFailsTheRequest() {
        DecisionResponse response = DecisionResponseAssembler.forSpec(spec, "svc").unsafe().build();

        assertEquals(FailureReason.INVALID_RESPONSE, response.getRequestFailure());
    }
}
