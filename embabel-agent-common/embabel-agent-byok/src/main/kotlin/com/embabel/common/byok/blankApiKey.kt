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
 * Message carried by [InvalidApiKeyException] when a key is present but blank.
 */
val BLANK_API_KEY_MESSAGE: String = """
    API key is blank. A blank key is treated as absent:
    supply a non-empty key, or omit it and let the caller decide there is no key for this request.
""".trimIndent()

/**
 * Rejects a blank or absent API key before it reaches a provider.
 *
 * A key is blank rather than absent more often than it looks. Docker Compose passes
 * `ANTHROPIC_API_KEY=${'$'}{ANTHROPIC_API_KEY:-}`, so in a container the variable is routinely
 * set-but-empty, and a caller reading it with a null default (`@Value("${'$'}{VAR:#{null}}")`,
 * `System.getenv(...)`) gets `""` rather than null. That empty string passes a null check,
 * reaches the provider, and returns an opaque authentication error far from its cause.
 *
 * Checking here means BYOK callers do not each re-derive "blank counts as absent", and get the
 * same [InvalidApiKeyException] they already handle for a rejected key.
 *
 * Note this concerns the BYOK path only. Provider autoconfiguration documents its API key as
 * required and should keep failing fast at startup when one is missing.
 *
 * @throws InvalidApiKeyException if [apiKey] is null, empty, or whitespace.
 */
fun requireUsableApiKey(apiKey: String?) {
    if (apiKey.isNullOrBlank()) {
        throw InvalidApiKeyException(BLANK_API_KEY_MESSAGE)
    }
}
