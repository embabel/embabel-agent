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
import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.ModelSelectionCriteria;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Java callers building decision services from a model they already hold or from a model name.
 * The tagged regions are included in the reference docs.
 */
class LlmDecisionServiceJavaTest {

    private final LlmService<?> llm = new SpringAiLlmService("gpt-test", "TestProvider", mock(ChatModel.class));

    private final ModelProvenance provenance = new ModelProvenance("gpt-test", "TestProvider");

    private final LlmOperations llmOperations = mock(LlmOperations.class);

    private final ModelProvider modelProvider = mock(ModelProvider.class);

    private final ObservationRegistry observationRegistry = ObservationRegistry.create();

    // Applications inject this bean from the Spring context rather than constructing it themselves.
    private final LlmDecisionServiceFactory factory =
            new LlmDecisionServiceFactory(llmOperations, modelProvider, observationRegistry);

    /** Stands in for the platform's operations by reading one canned model reply into the answer type asked for. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void modelReplies(String json) {
        var mapper = EmbabelObjectMapperHolder.createDefault().get();
        when(llmOperations.doTransform(anyList(), any(LlmInteraction.class), any(Class.class), isNull()))
                .thenAnswer(call -> mapper.readValue(json, (Class) call.getArgument(2)));
    }

    @Test
    void suppliedModel() {
        modelReplies("{\"verdict\":\"TRUE\"}");

        // tag::supplied[]
        var decisions = factory.decisionService(llm);
        var result = decisions.assess(
                new PropositionRequest("My card was charged twice", "The customer wants a refund"));
        // end::supplied[]

        assertEquals(new PropositionResult.Answered(true, provenance), result);
    }

    @Test
    void namedModel() {
        when(modelProvider.getLlm(ModelSelectionCriteria.byName("gpt-test"))).thenAnswer(call -> llm);
        modelReplies("{\"verdict\":\"SELECTED\",\"categoryId\":\"billing\"}");

        // tag::named[]
        var classifier = factory.classificationService("gpt-test");
        var result = classifier.classify(new ClassificationRequest(
                "My card was charged twice",
                List.of(
                        new Category("billing", "Payments, invoices and refunds"),
                        new Category("technical", "Errors, outages and bugs"))));
        // end::named[]

        assertEquals(new ClassificationResult.Selected("billing", provenance), result);
        verify(modelProvider, times(1)).getLlm(ModelSelectionCriteria.byName("gpt-test"));
    }

    @Test
    void tunedRetry() {
        modelReplies("{\"verdict\":\"FALSE\"}");

        var retry = new LlmDecisionRetryProperties(3, 200L);
        var factory = new LlmDecisionServiceFactory(llmOperations, modelProvider, ObservationRegistry.NOOP, retry);

        assertEquals(new PropositionResult.Answered(false, provenance), factory.decisionService(llm).assess(
                new PropositionRequest("My card was charged twice", "The customer is happy")));
        assertEquals("embabel.agent.platform.decisions.llm", retry.getPropertyPrefix());
    }
}
