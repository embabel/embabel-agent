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

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Concurrently attempts each candidate [ByokFactory] and returns the first service of type [T]
 * that validates successfully. Remaining tasks are cancelled on first success.
 *
 * Typical usage — sign-up flow (fan-out across all supported BYOK providers):
 * ```kotlin
 * val service = detectProvider(
 *     AnthropicModelFactory(apiKey = userKey),
 *     OpenAiCompatibleModelFactory.openAi(userKey),
 *     OpenAiCompatibleModelFactory.deepSeek(userKey),
 *     OpenAiCompatibleModelFactory.mistral(userKey),
 *     OpenAiCompatibleModelFactory.gemini(userKey),
 * )
 * val detectedProvider = service.provider
 * ```
 *
 * Typical usage — settings flow (single known provider):
 * ```kotlin
 * val service = detectProvider(AnthropicModelFactory(apiKey = userKey))
 * ```
 *
 * @param candidates One or more [ByokFactory] instances to race.
 * @return The service returned by the first successful factory.
 * @throws IllegalArgumentException if no candidates are supplied.
 * @throws InvalidApiKeyException if all candidates fail validation.
 */
fun <T> detectProvider(vararg candidates: ByokFactory<T>): T {
    require(candidates.isNotEmpty()) { "At least one ByokFactory candidate is required" }
    val exec = Executors.newVirtualThreadPerTaskExecutor()
    try {
        return exec.invokeAny(
            candidates.map { factory -> Callable { factory.buildValidated() } }
        )
    } catch (e: ExecutionException) {
        throw InvalidApiKeyException("Key not valid for any supported provider")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InvalidApiKeyException("Key not valid for any supported provider")
    } finally {
        exec.shutdown()
    }
}
