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
package com.embabel.common.core.streaming

import com.embabel.agent.api.common.PromptRunner

/**
 * Tag interface that marks streaming capability support.
 *
 * This interface serves as a marker for objects that provide streaming operations,
 * enabling polymorphic access to streaming functionality without creating circular
 * dependencies between API packages.
 *
 * Implementations of this interface provide reactive streaming capabilities that
 * allow for real-time processing of LLM responses as they arrive, supporting:
 * - Progressive text generation
 * - Streaming object creation from JSONL responses
 * - Mixed content streams with both objects and LLM reasoning (thinking)
 *
 * Usage:
 * ```kotlin
 * val runner: PromptRunner = context.ai().autoLlm()
 * if (runner.supportsStreaming()) {
 *     val capability: StreamingCapability = runner.stream()
 *     val operations = capability as StreamingPromptRunnerOperations (or use asStreaming extension function)
 *     // Use streaming operations...
 * }
 * ```
 *
 * This interface follows the explicit failure policy - streaming operations
 * will throw exceptions if called on non-streaming implementations rather
 * than providing fallback behavior.
 *
 */
@Deprecated(
    message = "Use PromptRunner.StreamingCapability instead",
    replaceWith = ReplaceWith(
        expression = "PromptRunner.StreamingCapability",
        imports = arrayOf("com.embabel.agent.api.common.PromptRunner.StreamingCapability"),
    )
)
interface StreamingCapability : PromptRunner.StreamingCapability {
    // Tag interface - no methods required
    // Concrete implementations provide the actual streaming functionality
}
