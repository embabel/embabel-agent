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
package com.embabel.agent.api.common;

import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.ModelSelectionCriteria;
import com.embabel.common.ai.model.ServiceSelectionException;
import com.embabel.common.ai.model.ServiceSelector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Selects decision services through {@link Ai} from Java.
 */
class AiDecisionSelectorsJavaTest {

    private final ModelProvenance provenance = new ModelProvenance("stub-model", "stub");

    private final PropositionQuestionSpec urgent =
        Questions.named("urgent").proposition("Is the customer asking for urgent help?").build();

    private final ChoiceQuestionSpec team = Questions.named("team")
        .choice("Which team should handle this?")
        .option("billing", "Payments and invoices")
        .option("support", "Product help")
        .build();

    private final PropositionResult urgentAnswer = new PropositionResult.Answered(true, provenance);

    private final ClassificationResult teamAnswer = new ClassificationResult.Selected("billing", provenance);

    private final StubDecisionService stub = StubDecisionService.builder("triage-stub")
        .proposition("urgent", urgentAnswer)
        .choice("team", teamAnswer)
        .build();

    private final DecisionServiceRegistry registry = DecisionServiceRegistry.builder()
        .register("triage-stub", stub)
        .decisionRole("support-triage", "triage-stub")
        .build();

    @Test
    void selectsByRoleNameDefaultAndInstance() {
        Ai ai = FakeOperationContext.withDecisionServices(registry).ai();
        ServiceSelector<DecisionService> decisions = ai.decisions();
        DecisionSpec triage = DecisionSpec.of(urgent, team);

        DecisionResponse response = decisions.byRole("support-triage").ask("I was charged twice.", triage);
        assertEquals(urgentAnswer, response.answer(urgent));
        assertEquals(teamAnswer, response.answer(team));

        assertEquals("triage-stub", decisions.named("triage-stub").getName());
        assertEquals("triage-stub", decisions.defaultService().getName());
        assertEquals("triage-stub", decisions.using(stub).getName());

        ServiceSelector<ClassificationService> classifications = ai.classifications();
        assertEquals("triage-stub", classifications.named("triage-stub").getName());
    }

    @Test
    void missingRoleThrowsSelectionError() {
        ServiceSelector<DecisionService> decisions =
            FakeOperationContext.withDecisionServices(registry).ai().decisions();
        ServiceSelectionException error =
            assertThrows(ServiceSelectionException.class, () -> decisions.byRole("billing-review"));
        assertEquals(ServiceSelectionException.Reason.UNKNOWN_ROLE, error.getReason());
    }

    @Test
    void anAiWithOnlyTheAbstractMethodsSelectsASuppliedService() {
        Ai ai = new MinimalAi();
        assertSame(stub, ai.decisions().using(stub));
        assertSame(stub, ai.classifications().using(stub));
        ServiceSelector<DecisionService> decisions = ai.decisions();
        assertThrows(ServiceSelectionException.class, decisions::defaultService);
    }

    /**
     * An implementation that defines only the abstract methods of {@link Ai}.
     */
    static final class MinimalAi implements Ai {

        @Override
        public EmbeddingService withEmbeddingService(ModelSelectionCriteria criteria) {
            throw new UnsupportedOperationException("no embedding services");
        }

        @Override
        public PromptRunner withLlm(LlmOptions llm) {
            throw new UnsupportedOperationException("no LLMs");
        }
    }
}
