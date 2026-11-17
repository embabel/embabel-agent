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

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DecisionCapabilitiesJavaTest {

    @Test
    void chainBuildsCapabilitiesAndEveryDeclarationReadsBack() {
        DecisionOption<String> effort = DecisionOption.of("acme", "effort", String.class);

        DecisionCapabilities capabilities = DecisionCapabilities.of(EnumSet.of(QuestionKind.CHOICE, QuestionKind.RATING))
                .acceptingInput("text")
                .acceptingOption(DecisionOptions.TIMEOUT)
                .acceptingOption(effort)
                .withMaxQuestions(4)
                .withArity(QuestionKind.CHOICE, 1, 2)
                .reporting(QuestionKind.CHOICE, DecisionEvidenceKind.CATEGORY_DISTRIBUTION, DecisionEvidenceKind.REFUSAL)
                .reporting(QuestionKind.RATING, DecisionEvidenceKind.EXPECTED_LEVEL_INDEX)
                .withConfidenceMeasure(QuestionKind.RATING, new ConfidenceMeasure("acme.concentration"));

        assertEquals(EnumSet.of(QuestionKind.CHOICE, QuestionKind.RATING), capabilities.getQuestionKinds());
        assertEquals(Set.of("text"), capabilities.getNativeInputKinds());
        assertEquals(Integer.valueOf(4), capabilities.getMaxQuestions());
        assertEquals(Set.of("embabel.timeout", "acme.effort"), capabilities.getSupportedCallOptionIds());

        DecisionArity arity = capabilities.arity(QuestionKind.CHOICE);
        assertEquals(1, arity.getMin());
        assertEquals(2, arity.getMax());
        assertNull(capabilities.arity(QuestionKind.RATING));

        assertEquals(
                EnumSet.of(DecisionEvidenceKind.CATEGORY_DISTRIBUTION, DecisionEvidenceKind.REFUSAL),
                capabilities.evidence(QuestionKind.CHOICE));
        assertEquals(EnumSet.of(DecisionEvidenceKind.EXPECTED_LEVEL_INDEX), capabilities.evidence(QuestionKind.RATING));

        assertEquals("acme.concentration", capabilities.confidenceMeasure(QuestionKind.RATING).getId());
        assertNull(capabilities.confidenceMeasure(QuestionKind.CHOICE));
    }

    @Test
    void kindsOnlyCapabilitiesStateNothingElse() {
        DecisionCapabilities capabilities = DecisionCapabilities.of(Set.of(QuestionKind.PROPOSITION));
        assertNull(capabilities.getNativeInputKinds());
        assertNull(capabilities.getMaxQuestions());
        assertNull(capabilities.getSupportedCallOptionIds());
        assertNull(capabilities.evidence(QuestionKind.PROPOSITION));
    }
}
