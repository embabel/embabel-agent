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
package com.embabel.agent.core.internal.streaming

import com.embabel.common.ai.model.LlmOptions
import org.jetbrains.annotations.ApiStatus

/**
 * Factory interface for creating [StreamingLlmOperations] instances.
 *
 * This interface is separate from [com.embabel.agent.core.internal.LlmOperations]
 * to maintain interface segregation. Implementations can choose to implement
 * both interfaces or just one, depending on their capabilities.
 *
 * @see StreamingLlmOperations
 * @see com.embabel.agent.core.internal.LlmOperations
 */
@ApiStatus.Internal
interface StreamingLlmOperationsFactory {

    /**
     * Check if streaming is supported for the given LLM options.
     *
     * This method allows capability detection without resolving specific
     * implementation details, enabling proper mocking in tests.
     *
     * @param options LLM options including model selection criteria
     * @return true if streaming is supported for the resolved LLM
     */
    fun supportsStreaming(options: LlmOptions): Boolean

    /**
     * Create a [StreamingLlmOperations] instance configured with the given options.
     *
     * @param options LLM options including model selection criteria
     * @return A streaming operations instance for the selected LLM
     */
    fun createStreamingOperations(options: LlmOptions): StreamingLlmOperations
}
