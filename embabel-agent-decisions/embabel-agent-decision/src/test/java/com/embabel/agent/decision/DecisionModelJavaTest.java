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
package com.embabel.agent.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionModelJavaTest {

    @Test
    void javaConsumerReadsAChoiceWithoutACast() {
        DecisionRequest.Builder request = DecisionRequest.builder();
        ChoiceKey<String> relation = request.choice("relation", "Relation?", List.of(
            DecisionOption.of("same", "IDENTICAL", "identical"),
            DecisionOption.of("other", "OTHER", "other")
        ));
        DecisionModel model = new DecisionModel(prepared -> RawDecisionOutcome.success(List.of(
            RawAnswer.distribution("relation", DecisionKind.CHOICE, List.of(
                RawProbability.of("same", 1.0), RawProbability.of("other", 0.0)
            ), "same")
        ), DecisionProvenance.builder("java-test", EvidenceKind.DISTRIBUTION).build()));

        DecisionOutcome.Success outcome = assertInstanceOf(DecisionOutcome.Success.class, model.ask(request.build()));
        KeyOutcome.Success<?> answer = assertInstanceOf(KeyOutcome.Success.class, outcome.answer(relation));

        assertEquals("same", answer.getValue());
        assertEquals("same", answer.getFirstMaximizer());
        assertEquals("java-test", outcome.getProvenance().getProvider());
    }
}
