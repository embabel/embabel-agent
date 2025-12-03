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
package com.embabel.agent.api.common.streaming

import com.embabel.agent.api.common.PromptRunner

/**
 * Extension of PromptRunner that provides streaming capabilities for progressive
 * LLM response processing. Enables real-time object creation and thinking streams.
 *
 * Implementations of this interface guarantee streaming support and override
 * the base PromptRunner behavior to provide real streaming operations.
 *
 * Usage:
 * ```kotlin
 * val streamingRunner = context.ai().withAutoLlm() as StreamingPromptRunner
 * val restaurantStream = streamingRunner.stream()
 *     .withPrompt("Find 5 restaurants in Paris")
 *     .createObjectStream(Restaurant::class.java)
 * ```
 */
interface StreamingPromptRunner : PromptRunner {

    /**
     * StreamingPromptRunner implementations always support streaming.
     * Overrides the base PromptRunner default of false.
     */
    override fun supportsStreaming(): Boolean = true

    /**
     * Create streaming operations for this prompt runner configuration.
     * @return StreamingPromptRunnerOperations instance for building streaming requests
     */
    override fun stream(): StreamingPromptRunnerOperations
}
