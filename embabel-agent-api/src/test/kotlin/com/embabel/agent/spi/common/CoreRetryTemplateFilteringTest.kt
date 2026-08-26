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
package com.embabel.agent.spi.common

import com.embabel.agent.spi.support.springai.SpringAiRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.retry.context.RetryContextSupport
import java.util.concurrent.atomic.AtomicInteger

/**
 * B1: Spring AI 2.0 takes a Spring Framework 7 `org.springframework.core.retry.RetryTemplate`,
 * and the three model configurations each build one from a private `platformRetryTemplate()`
 * that sets only the back off. With no `includes`, no `excludes` and no `predicate`,
 * `DefaultRetryPolicy.shouldRetry` falls back to `matchIfEmpty` and returns true for every
 * throwable. A malformed request or a bad API key is replayed `maxAttempts` times.
 *
 * Spring AI's own default (`RetryUtils.createDefaultRetryTemplate`) uses an allow list; replacing
 * it to honour `maxAttempts` dropped that filter. These tests pin the filter onto the shared
 * `coreRetryTemplate`, which is where the three copies are meant to converge.
 */
class CoreRetryTemplateFilteringTest {

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MODEL = "gpt-4o"
    }

    private class TestRetryProperties(override val maxAttempts: Int) : RetryProperties {
        override val backoffMillis = 1L
        override val backoffMultiplier = 2.0
        override val backoffMaxInterval = 5L
        override val propertyPrefix = "embabel.agent.platform.models.test"
    }

    private val properties = TestRetryProperties(MAX_ATTEMPTS)

    /** Number of times the call was actually made through the core.retry template. */
    private fun attemptsFor(error: Throwable): Int {
        val attempts = AtomicInteger()
        val template = properties.coreRetryTemplate(MODEL)
        try {
            template.execute<Any> {
                attempts.incrementAndGet()
                throw error
            }
        } catch (expected: Throwable) {
            // core.retry wraps the last failure in a RetryException; the attempt count is the subject
        }
        return attempts.get()
    }

    /** What the spring-retry side of the platform decides for the same throwable. */
    private fun springRetryWouldRetry(error: Throwable): Boolean {
        val context = RetryContextSupport(null)
        context.registerThrowable(error)
        return SpringAiRetryPolicy(maxAttempts = MAX_ATTEMPTS).canRetry(context)
    }

    @Test
    fun `a transient upstream failure is retried up to maxAttempts`() {
        assertThat(attemptsFor(TransientAiException("503 - Upstream temporarily unavailable")))
            .describedAs("transient failures are what retry is for")
            .isEqualTo(MAX_ATTEMPTS)
    }

    @Test
    fun `a malformed request is not retried`() {
        assertThat(attemptsFor(NonTransientAiException("400 - Invalid value for 'max_tokens'")))
            .describedAs("a 400 is deterministic, replaying it only burns the back off")
            .isEqualTo(1)
    }

    @Test
    fun `an invalid api key is not retried`() {
        assertThat(attemptsFor(NonTransientAiException("401 - Incorrect API key provided")))
            .describedAs("a 401 is deterministic, replaying it only burns the back off")
            .isEqualTo(1)
    }

    @Test
    fun `a programming error is not retried`() {
        assertThat(attemptsFor(IllegalArgumentException("Unknown model 'gpt-4o-typo'")))
            .describedAs("an IllegalArgumentException never becomes valid on the next try")
            .isEqualTo(1)
    }

    @Test
    fun `a rate limit is retried whatever wording the provider uses`() {
        // Ties B1 to B2: the shared predicate is the one that must recognise these.
        listOf(
            "429 - Rate limit reached for $MODEL",
            "429 - Too many requests, please retry after 20s",
            "429 - Quota exceeded for quota metric 'Generate requests'",
        ).forEach { message ->
            assertThat(attemptsFor(NonTransientAiException(message)))
                .describedAs("rate limited: %s", message)
                .isEqualTo(MAX_ATTEMPTS)
        }
    }

    @Test
    fun `both retry paths reach the same verdict`() {
        listOf(
            TransientAiException("503 - Upstream temporarily unavailable"),
            NonTransientAiException("400 - Invalid value for 'max_tokens'"),
            NonTransientAiException("401 - Incorrect API key provided"),
            NonTransientAiException("429 - Too many requests, please retry after 20s"),
            IllegalArgumentException("Unknown model 'gpt-4o-typo'"),
            IllegalStateException("Tool callback registry not initialised"),
        ).forEach { error ->
            val coreRetried = attemptsFor(error) > 1
            assertThat(coreRetried)
                .describedAs(
                    "core.retry and spring-retry must share one predicate, disagreed on %s: %s",
                    error::class.simpleName, error.message,
                )
                .isEqualTo(springRetryWouldRetry(error))
        }
    }
}
