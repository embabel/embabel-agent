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
package com.embabel.agent.spi.decision;

import com.embabel.agent.core.internal.LlmOperations;
import com.embabel.agent.core.support.LlmInteraction;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.model.ModelProvider;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Java callers asking a factory-built decision service a whole question set and reading typed answers.
 */
class LlmDecisionServiceAskJavaTest {

    private final LlmService<?> llm = new SpringAiLlmService("gpt-test", "TestProvider", mock(ChatModel.class));

    private final ModelProvenance provenance = new ModelProvenance("gpt-test", "TestProvider");

    private final LlmOperations llmOperations = mock(LlmOperations.class);

    // Applications inject this bean from the Spring context.
    private final LlmDecisionServiceFactory factory =
            new LlmDecisionServiceFactory(llmOperations, mock(ModelProvider.class), ObservationRegistry.create());

    private final PropositionQuestionSpec urgent =
            Questions.named("urgent").proposition("Does this convey urgency?").build();

    private final ChoiceQuestionSpec department = Questions.named("department")
            .choice("Which team should handle this?")
            .option("billing", "Payments, invoicing, refunds")
            .option("technical", "Bugs, outages, integrations")
            .build();

    private final RatingQuestionSpec frustration = Questions.named("frustration")
            .rating("How frustrated is the customer?")
            .level("calm", "No sign of frustration")
            .level("frustrated", "Clearly annoyed")
            .level("angry", "Hostile or threatening")
            .build();

    @Test
    void factoryBuiltServiceAnswersAQuestionSet() {
        when(llmOperations.doTransform(anyList(), any(LlmInteraction.class), eq(String.class), isNull()))
                .thenReturn("{\"answers\":["
                        + "{\"question\":\"q3\",\"verdict\":\"RATED\",\"levelId\":\"angry\"},"
                        + "{\"question\":\"q1\",\"verdict\":\"FALSE\"},"
                        + "{\"question\":\"q2\",\"verdict\":\"NO_MATCH\",\"categoryId\":null}]}");

        var decisions = factory.decisionService(llm);
        var triage = DecisionSpec.of(urgent, department, frustration);
        DecisionResponse response = decisions.ask("My card was charged twice and nobody answers!", triage);

        assertEquals(new PropositionResult.Answered(false, provenance), response.answer(urgent));
        assertEquals(new ClassificationResult.NoMatch(provenance), response.answer(department));
        RatingResult rating = response.answer(frustration);
        assertEquals(new RatingResult.Answered(provenance, "angry"), rating);
        verify(llmOperations, times(1)).doTransform(anyList(), any(LlmInteraction.class), eq(String.class), isNull());
    }
}
