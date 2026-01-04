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

import com.embabel.agent.api.common.support.OperationContextPromptRunner;
import com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Simple test for ThinkingPromptRunnerBuilder Java API.
 * <p>
 * Verifies that Java code can successfully access thinking functionality
 * through the builder pattern.
 */
class ThinkingPromptRunnerBuilderTest {

    @Test
    void testBuilderCreatesThinkingOperations() {
        // Given: A mock OperationContextPromptRunner (thinking-capable)
        OperationContextPromptRunner runner = mock(OperationContextPromptRunner.class);
        ThinkingPromptRunnerOperations mockThinkingOps = mock(ThinkingPromptRunnerOperations.class);
        when(runner.withThinking()).thenReturn(mockThinkingOps);

        // When: Using the builder to create thinking operations
        ThinkingPromptRunnerOperations thinkingOps = new ThinkingPromptRunnerBuilder(runner)
                .withThinking();

        // Then: Should return valid ThinkingPromptRunnerOperations
        assertNotNull(thinkingOps);
    }

    @Test
    void testFactoryMethodCreatesThinkingOperations() {
        // Given: A mock OperationContextPromptRunner (thinking-capable)
        OperationContextPromptRunner runner = mock(OperationContextPromptRunner.class);
        ThinkingPromptRunnerOperations mockThinkingOps = mock(ThinkingPromptRunnerOperations.class);
        when(runner.withThinking()).thenReturn(mockThinkingOps);

        // When: Using the static factory method
        ThinkingPromptRunnerOperations thinkingOps = ThinkingPromptRunnerBuilder
                .from(runner)
                .withThinking();

        // Then: Should create thinking operations successfully
        assertNotNull(thinkingOps);
    }
}