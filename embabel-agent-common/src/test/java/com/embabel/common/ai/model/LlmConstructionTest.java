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
package com.embabel.common.ai.model;

import com.embabel.common.ai.prompt.KnowledgeCutoffDate;
import com.embabel.common.ai.prompt.PromptContributor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test simplified construction of Llm instances in Java
 */
public class LlmConstructionTest {

    @Test
    void demonstrateJavaConstructionWithRequiredParameters() {
        // Create a mock ChatModel for testing
        ChatModel mockChatModel = mock(ChatModel.class);

        // Construct Llm with the 3 required parameters
        var llm = new Llm(
                "gpt-4",           // name
                "OpenAI",          // provider
                mockChatModel      // model
        );

        // Verify construction
        assertNotNull(llm);
        assertEquals("gpt-4", llm.getName());
        assertEquals("OpenAI", llm.getProvider());
        assertSame(mockChatModel, llm.getModel());
        assertNotNull(llm.getOptionsConverter());
        assertNull(llm.getKnowledgeCutoffDate());
        assertNotNull(llm.getPromptContributors());
        assertTrue(llm.getPromptContributors().isEmpty());
        assertNull(llm.getPricingModel());
    }

    @Test
    void demonstrateJavaModificationWithWithers() {
        // Create a mock ChatModel for testing
        ChatModel mockChatModel = mock(ChatModel.class);

        // Construct initial Llm
        var llm = new Llm(
                "gpt-4",
                "OpenAI",
                mockChatModel
        );

        // Modify using withKnowledgeCutoffDate wither
        var cutoffDate = LocalDate.of(2024, 1, 1);
        var llmWithCutoff = llm.withKnowledgeCutoffDate(cutoffDate);

        // Verify the wither created a new instance with updated values
        assertNotSame(llm, llmWithCutoff);
        assertEquals(cutoffDate, llmWithCutoff.getKnowledgeCutoffDate());
        assertEquals(1, llmWithCutoff.getPromptContributors().size());
        assertTrue(llmWithCutoff.getPromptContributors().get(0) instanceof KnowledgeCutoffDate);

        // Modify using withOptionsConverter wither
        OptionsConverter<?> customConverter = DefaultOptionsConverter.INSTANCE;
        var llmWithConverter = llmWithCutoff.withOptionsConverter(customConverter);

        // Verify the wither created a new instance
        assertNotSame(llmWithCutoff, llmWithConverter);
        assertSame(customConverter, llmWithConverter.getOptionsConverter());

        // Modify using withPromptContributor wither
        PromptContributor customContributor = mock(PromptContributor.class);
        var llmWithContributor = llmWithConverter.withPromptContributor(customContributor);

        // Verify the wither added the contributor
        assertNotSame(llmWithConverter, llmWithContributor);
        assertEquals(2, llmWithContributor.getPromptContributors().size());
        assertTrue(llmWithContributor.getPromptContributors().contains(customContributor));
    }

    @Test
    void demonstrateJavaFluentWitherChaining() {
        // Create a mock ChatModel for testing
        ChatModel mockChatModel = mock(ChatModel.class);

        // Demonstrate fluent chaining of withers
        var llm = new Llm("gpt-4", "OpenAI", mockChatModel)
                .withKnowledgeCutoffDate(LocalDate.of(2024, 1, 1))
                .withOptionsConverter(DefaultOptionsConverter.INSTANCE)
                .withPromptContributor(mock(PromptContributor.class));

        // Verify all modifications were applied
        assertNotNull(llm);
        assertEquals("gpt-4", llm.getName());
        assertEquals("OpenAI", llm.getProvider());
        assertNotNull(llm.getKnowledgeCutoffDate());
        assertEquals(2, llm.getPromptContributors().size());
    }
}
