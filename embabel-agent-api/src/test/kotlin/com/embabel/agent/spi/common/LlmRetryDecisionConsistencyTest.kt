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

import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.agent.spi.support.springai.SpringAiRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.retry.context.RetryContextSupport

/**
 * B2: rate-limit detection is implemented twice, with two different lists.
 *
 * - [LlmDataBindingProperties.isRateLimitError] knows five patterns and drives the *log*.
 * - [SpringAiRetryPolicy] knows two phrases ("rate limit", "rate-limit") and drives the *decision*.
 *
 * The two must agree: whatever we announce as rate limited must actually be retried, and
 * whatever we retry as a rate limit must actually be one. These tests assert that invariant
 * directly on the two existing entry points, so they keep their meaning once both delegate
 * to a single `LlmRetryDecision`.
 */
class LlmRetryDecisionConsistencyTest {

    private val policy = SpringAiRetryPolicy(maxAttempts = 3)

    /**
     * One failure registered, well below maxAttempts, so the counter never masks the
     * classification we are actually testing.
     */
    private fun retried(t: Throwable): Boolean {
        val context = RetryContextSupport(null)
        context.registerThrowable(t)
        return policy.canRetry(context)
    }

    private fun announcedAsRateLimited(t: Throwable): Boolean =
        LlmDataBindingProperties.isRateLimitError(t)

    @Nested
    inner class WhatWeAnnounceAsRateLimitedMustBeRetried {

        /**
         * OpenAI and Mistral word their 429 body "Rate limit reached". Already consistent today,
         * kept as the control case: the fix must not regress it.
         */
        @Test
        fun `rate limit reached`() {
            val e = NonTransientAiException("429 - Rate limit reached for gpt-4o in organization org-abc")
            assertThat(announcedAsRateLimited(e)).isTrue()
            assertThat(retried(e))
                .describedAs("announced as rate limited, so it must be retried")
                .isTrue()
        }

        /**
         * Anthropic and DeepSeek word their 429 body "Too many requests". The log says
         * RATE LIMITED, the policy sees a NonTransientAiException without the phrase
         * "rate limit" and gives up on the first attempt.
         */
        @Test
        fun `too many requests`() {
            val e = NonTransientAiException("429 - Too many requests, please retry after 20s")
            assertThat(announcedAsRateLimited(e)).isTrue()
            assertThat(retried(e))
                .describedAs("announced as rate limited, so it must be retried")
                .isTrue()
        }

        /**
         * Google GenAI words its 429 body "Quota exceeded". Same divergence.
         */
        @Test
        fun `quota exceeded`() {
            val e = NonTransientAiException("429 - Quota exceeded for quota metric 'Generate requests'")
            assertThat(announcedAsRateLimited(e)).isTrue()
            assertThat(retried(e))
                .describedAs("announced as rate limited, so it must be retried")
                .isTrue()
        }
    }

    /**
     * The bare "429" pattern matches any three digits anywhere in the message. It is harmless
     * while it only picks a log line, but the two lists are about to be merged into the retry
     * decision, so it must stop matching digits that are not a status code.
     */
    @Nested
    inner class DigitsThatAreNotAStatusCode {

        @Test
        fun `token count containing 429 is not a rate limit`() {
            val e = NonTransientAiException(
                "400 - Invalid value for 'max_tokens': 4290 is greater than the maximum allowed"
            )
            assertThat(announcedAsRateLimited(e))
                .describedAs("a 400 about max_tokens is not a rate limit")
                .isFalse()
            assertThat(retried(e))
                .describedAs("a malformed request never succeeds on retry")
                .isFalse()
        }

        @Test
        fun `request id containing 429 is not a rate limit`() {
            val e = IllegalArgumentException("No tool callback found for call_429fa1c2")
            assertThat(announcedAsRateLimited(e))
                .describedAs("a tool wiring error is not a rate limit")
                .isFalse()
            assertThat(retried(e))
                .describedAs("a programming error never succeeds on retry")
                .isFalse()
        }
    }
}
