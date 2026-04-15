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
package com.embabel.common.byok

/**
 * A self-contained spec that can validate an API key and return a ready service of type [T].
 *
 * Each instance encapsulates the provider endpoint, credentials, and the model to use for
 * the validation probe. Implementations throw [InvalidApiKeyException] on failure so callers
 * never need to unwrap provider-specific error types.
 *
 * The type parameter [T] allows the same pattern to be reused for any BYOK-validated service
 * (e.g. `ByokFactory<LlmService<*>>` for chat models, or other validated provider services).
 *
 * Pass one or more instances to [detectProvider] to race them concurrently.
 */
fun interface ByokFactory<out T> {
    /**
     * Validates the configured API key with a single probe call and returns a production
     * service of type [T] on success.
     *
     * @throws InvalidApiKeyException if the key is invalid or the provider is unreachable.
     */
    fun buildValidated(): T
}
