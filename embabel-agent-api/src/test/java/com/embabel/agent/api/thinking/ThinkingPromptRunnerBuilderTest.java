/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.thinking;

import com.embabel.agent.api.common.PromptRunnerOperations;
import com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Simple test for ThinkingPromptRunnerBuilder Java API.
 * 
 * Verifies that Java code can successfully access thinking functionality
 * through the builder pattern.
 */
public class ThinkingPromptRunnerBuilderTest {

    @Test
    public void testBuilderCreatesThinkingOperations() {
        // Given: A mock prompt runner operations
        PromptRunnerOperations runner = mock(PromptRunnerOperations.class);
        
        // When: Using the builder to create thinking operations
        ThinkingPromptRunnerOperations thinkingOps = new ThinkingPromptRunnerBuilder(runner)
            .withThinking();
        
        // Then: Should return valid ThinkingPromptRunnerOperations
        assertNotNull(thinkingOps);
    }

    @Test
    public void testFactoryMethodCreatesThinkingOperations() {
        // Given: A mock prompt runner operations
        PromptRunnerOperations runner = mock(PromptRunnerOperations.class);
        
        // When: Using the static factory method
        ThinkingPromptRunnerOperations thinkingOps = ThinkingPromptRunnerBuilder
            .from(runner)
            .withThinking();
        
        // Then: Should create thinking operations successfully
        assertNotNull(thinkingOps);
    }
}