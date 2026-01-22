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
package com.embabel.agent.api.streaming;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations;
import com.embabel.common.core.streaming.StreamingCapability;

import java.util.Objects;

/**
 * Builder pattern to provide Java equivalent of Kotlin's asStreaming() extension function.
 * Solves the problem that Java cannot directly call Kotlin extension functions.
 */
public record StreamingPromptRunnerBuilder(PromptRunner runner) {

    /**
     * Java equivalent of Kotlin's withStreaming() extension function.
     * Provides type-safe access to streaming operations.
     */
    public StreamingPromptRunnerOperations withStreaming() {
        if (!runner.supportsStreaming()) {
            throw new UnsupportedOperationException(
                    "This LLM does not support streaming: " + Objects.requireNonNull(runner.getLlm()).getCriteria()
            );
        }

        StreamingCapability capability = runner.streaming();
        if (capability instanceof StreamingPromptRunnerOperations
                streamingPromptRunnerOperations) {
            return streamingPromptRunnerOperations;
        }

        throw new IllegalStateException(
                "Unexpected streaming capability implementation: " + capability.getClass()
        );
    }
}


