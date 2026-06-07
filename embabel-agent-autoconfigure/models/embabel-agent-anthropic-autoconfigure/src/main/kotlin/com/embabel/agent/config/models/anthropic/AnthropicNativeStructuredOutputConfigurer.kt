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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.spi.support.springai.SpringAiNativeStructuredOutputConfigurer
import org.springframework.ai.anthropic.AnthropicChatOptions

/**
 * Anthropic-specific native structured-output bridge.
 *
 * This is a provider-module hook, not core logic. It currently translates the
 * neutral Embabel structured-output request into Spring AI's Anthropic option
 * objects, which Spring AI then serializes to the Anthropic payload shape.
 * It does not directly inject provider tags into the raw request payload yet.
 *
 * The YAML tag metadata in the model files is documented for now and is not yet
 * the source of truth for direct payload injection.
 */
@Suppress("UNUSED_PARAMETER")
internal val AnthropicNativeStructuredOutputConfigurer = SpringAiNativeStructuredOutputConfigurer {
        options,
        structuredOutput,
        nativeSupport,
        _,
    ->
    nativeSupport?.structuredOutput?.let { capability ->
        if (structuredOutput == null || capability.supported != true) {
            options
        } else if (options !is AnthropicChatOptions) {
            options
        } else if (capability.strategy != null && capability.strategy != "tool") {
            options
        } else {
            options.copy().also { copied ->
                copied.outputSchema = structuredOutput.schema
            }
        }
    } ?: options
}
