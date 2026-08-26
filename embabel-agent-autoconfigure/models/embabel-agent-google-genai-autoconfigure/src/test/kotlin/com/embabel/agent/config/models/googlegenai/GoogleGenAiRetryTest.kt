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
package com.embabel.agent.config.models.googlegenai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.core.retry.Retryable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks what the Spring Framework 7 retry template actually replays. A rate limit must be retried;
 * a malformed request must not. Counting invocations is what separates the two — the caller sees a
 * failure either way.
 *
 * The Mistral and DeepSeek equivalents drive the same two cases over HTTP. Here the failures are
 * raised inside the template, because Spring AI renders an HTTP failure as "<status> - <body>" and
 * that string is all the decision has to go on.
 */
class GoogleGenAiRetryTest {

    private fun attemptsBeforeGivingUp(failure: Throwable): Int {
        val attempts = AtomicInteger()
        googleGenAiRunner(
            "embabel.agent.platform.models.googlegenai.max-attempts=3",
            // Keep the backoff short enough that the test does not idle.
            "embabel.agent.platform.models.googlegenai.backoff-millis=50",
            "embabel.agent.platform.models.googlegenai.backoff-multiplier=2",
            "embabel.agent.platform.models.googlegenai.backoff-max-interval=200",
        ).run { context ->
            assertThrows<Throwable> {
                retryTemplateOf(context).execute(Retryable<Any> {
                    attempts.incrementAndGet()
                    throw failure
                })
            }
        }
        return attempts.get()
    }

    @Test
    fun `a rate limit is retried up to the configured maximum`() {
        assertEquals(
            3, attemptsBeforeGivingUp(NonTransientAiException("429 - Requests rate limit exceeded")),
            "a 429 is worth replaying, so all three configured attempts are spent",
        )
    }

    @Test
    fun `a transient failure is retried up to the configured maximum`() {
        assertEquals(
            3, attemptsBeforeGivingUp(TransientAiException("upstream unavailable")),
            "a transient failure is worth replaying",
        )
    }

    @Test
    fun `a malformed request is not retried`() {
        assertEquals(
            1, attemptsBeforeGivingUp(NonTransientAiException("400 - Invalid model: no-such-model")),
            "a 400 is deterministic, so it must fail on the first attempt",
        )
    }
}
