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
 * Thrown when an API key is invalid or not recognised by any supported provider.
 * [detectProvider] and the factory [ByokFactory.buildValidated] methods throw it, so a caller
 * catches this one type and does not catch a provider-specific type, such as a Spring AI or
 * SDK exception.
 *
 * A [cause] may carry the provider's own exception. It is there for diagnosis — a log line that
 * shows WHY a probe failed — and callers still catch this type alone.
 *
 * [statusCode] is the HTTP status code of the provider's response. It is null when the provider
 * sent no response, for example when the connection was refused, and when the key was rejected
 * before any request was made, as a blank key is.
 *
 * The status code shows why the provider refused the key, so a caller does not have to read the
 * message to find out. Providers do not use the same status code for the same reason, so a
 * caller must know which provider responded before it decides what the code means. These are
 * examples, not rules:
 * - The provider does not know the key: OpenAI, DeepSeek, Mistral and Anthropic respond 401.
 *   Google's OpenAI-compatible endpoint responds 400.
 * - The provider knows the key, and the account has no credit: Google responds 402.
 * - The provider knows the key, and the key is not permitted to make the request: 403.
 * - The provider knows the key, and the key is rate limited: 429.
 */
class InvalidApiKeyException(
    message: String,
    cause: Throwable?,
    val statusCode: Int?,
) : RuntimeException(message, cause) {

    /** Creates the exception with no status code. Use this when the provider sent no response. */
    @JvmOverloads
    constructor(message: String, cause: Throwable? = null) : this(message, cause, null)
}
