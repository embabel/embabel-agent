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

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionCapabilities;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.QuestionKind;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.model.DecisionService;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

class LegacyDecisionServiceJavaTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("legacy-model", "legacy");

    /** A service written against the base API only. */
    static final class LegacyJavaService implements DecisionService {

        int assessCalls;

        @Override
        public String getName() {
            return "legacy-java";
        }

        @Override
        public String getProvider() {
            return "legacy";
        }

        @Override
        public ClassificationResult classify(ClassificationRequest request) {
            return new ClassificationResult.Selected(request.getCategories().get(0).getId(), PROVENANCE);
        }

        @Override
        public PropositionResult assess(PropositionRequest request) {
            assessCalls++;
            return new PropositionResult.Answered(true, PROVENANCE);
        }
    }

    @Test
    void askAnswersOneProposition() {
        var service = new LegacyJavaService();
        PropositionQuestionSpec urgent = Questions.named("urgent").proposition("Is this urgent?").build();

        var response = service.ask("A customer email.", DecisionSpec.of(urgent));

        assertEquals(new PropositionResult.Answered(true, PROVENANCE), response.answer(urgent));
        assertEquals(1, service.assessCalls);
    }

    @Test
    void capabilitiesAreTheLegacyDescriptor() {
        var expected =
                DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE));
        assertEquals(expected, new LegacyJavaService().capabilities());
    }

    @Test
    void singleChoiceThroughAskGoesThroughClassify() {
        var service = new LegacyJavaService();
        ChoiceQuestionSpec team = Questions.named("team")
                .choice("Which team?")
                .option("support", "Help")
                .option("billing", "Payments")
                .build();

        var response = service.ask("A customer email.", DecisionSpec.of(team));

        assertEquals(new ClassificationResult.Selected("support", PROVENANCE), response.answer(team));
        assertEquals(0, service.assessCalls);
    }

    @Test
    void classificationRequestThroughAskAnswersItsQuestion() {
        var service = new LegacyJavaService();
        var departments = ClassificationSpec.builder()
                .asking("Which category fits?")
                .category("billing", "Payments")
                .build();

        var response = service.ask(ClassificationRequest.of("A customer email.", departments));

        assertEquals(new ClassificationResult.Selected("billing", PROVENANCE), response.answer(departments.getQuestion()));
    }

    @Test
    void singleRatingThroughAskIsUnsupported() {
        var service = new LegacyJavaService();
        var anger = Questions.named("anger").rating("How angry?").level("calm", "Calm").level("angry", "Angry").build();

        assertThrows(UnsupportedOperationException.class, () -> service.ask("A customer email.", DecisionSpec.of(anger)));
        assertEquals(0, service.assessCalls);
    }
}
