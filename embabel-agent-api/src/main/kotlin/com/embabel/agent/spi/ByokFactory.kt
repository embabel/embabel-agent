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
package com.embabel.agent.spi

/**
 * A self-contained spec that can validate an API key and return a ready [LlmService].
 *
 * Each instance encapsulates the provider endpoint, credentials, and the model to use for
 * the validation probe. Implementations throw [InvalidApiKeyException] on failure so callers
 * never need to unwrap provider-specific error types.
 *
 * Implementations are provided by:
 * - [com.embabel.agent.config.models.anthropic.AnthropicModelFactory] — Anthropic
 * - [com.embabel.agent.openai.OpenAiCompatibleModelFactory.ByokSpec] — OpenAI-compatible providers
 *   (OpenAI, DeepSeek, Mistral, Gemini, and custom providers)
 *
 * Pass one or more instances to [detectProvider] to race them concurrently.
 */
fun interface ByokFactory {
    /**
     * Validates the configured API key with a single probe call and returns a production
     * [LlmService] on success.
     *
     * @throws InvalidApiKeyException if the key is invalid or the provider is unreachable.
     */
    fun buildValidated(): LlmService<*>
}
