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

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations;
import com.embabel.common.core.thinking.ThinkingResponse;

/**
 * Builder pattern to provide Java equivalent of Kotlin's withThinking() extension function.
 * Solves the problem that Java cannot directly call Kotlin extension functions.
 *
 * <p>This builder enables Java developers to access thinking-aware prompt operations
 * that extract LLM reasoning blocks alongside converted results.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Java usage
 * ThinkingPromptRunnerOperations thinkingOps = new ThinkingPromptRunnerBuilder(promptRunner)
 *     .withThinking();
 *
 * ThinkingResponse<Person> result = thinkingOps.createObject("analyze this", Person.class);
 * Person person = result.getResult();              // The converted object
 * List<ThinkingBlock> thinking = result.getThinkingBlocks(); // LLM reasoning blocks
 * }</pre>
 *
 * <h2>Thinking Extraction</h2>
 *
 * <p>When supported by the underlying LLM operations (e.g., OperationContextPromptRunner
 * with ChatClientLlmOperations), automatically extracts thinking content in various formats:</p>
 *
 * <ul>
 * <li>Tagged thinking: {@code <think>reasoning here</think>}, {@code <analysis>content</analysis>}</li>
 * <li>Prefix thinking: {@code //THINKING: reasoning here}</li>
 * <li>Untagged thinking: raw text content before JSON objects</li>
 * </ul>
 *
 * <p>For implementations that don't support thinking extraction, returns graceful degradation
 * with empty thinking blocks while preserving the original response content.</p>
 *
 * @see ThinkingPromptRunnerOperations for available thinking-aware methods
 * @see ThinkingResponse for response structure
 * @see com.embabel.common.core.thinking.ThinkingBlock for thinking content details
 */
public record ThinkingPromptRunnerBuilder(PromptRunner runner) {

    /**
     * Java equivalent of Kotlin's withThinking() extension function.
     *
     * <p>Creates thinking-aware prompt operations that return both converted results
     * and the reasoning content that LLMs generate during processing.</p>
     *
     * <p><strong>Implementation Behavior:</strong></p>
     *
     * <ul>
     * <li><strong>OperationContextPromptRunner (Production):</strong> Real thinking extraction
     * using ChatClientLlmOperations with populated thinking blocks</li>
     * <li><strong>Other Implementations (Graceful Degradation):</strong> Returns wrapper with
     * empty thinking blocks while preserving original response content</li>
     * </ul>
     *
     * @return Enhanced prompt runner operations with thinking block extraction capabilities
     * @throws UnsupportedOperationException if the underlying LLM operations don't support
     *                                       thinking extraction and require ChatClientLlmOperations (only for specific implementations)
     *
     */
    public ThinkingPromptRunnerOperations withThinking() {
        return runner.withThinking();
    }

    /**
     * Factory method for creating a builder instance.
     *
     * <p>Provides a fluent interface for Java developers who prefer static factory methods:</p>
     *
     * <pre>{@code
     * ThinkingPromptRunnerOperations thinkingOps = ThinkingPromptRunnerBuilder
     *     .from(promptRunner)
     *     .withThinking();
     * }</pre>
     *
     * @param runner The prompt runner operations to enhance with thinking capabilities
     * @return A new ThinkingPromptRunnerBuilder instance
     */
    public static ThinkingPromptRunnerBuilder from(PromptRunner runner) {
        return new ThinkingPromptRunnerBuilder(runner);
    }
}