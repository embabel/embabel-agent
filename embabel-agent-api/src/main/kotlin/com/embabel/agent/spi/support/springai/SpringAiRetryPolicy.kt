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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.core.ReplanRequestedException
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryPolicy
import org.springframework.retry.context.RetryContextSupport

/**
 * Retry policy for Spring AI operations.
 */
internal class SpringAiRetryPolicy(
    private val maxAttempts: Int,
    private val rateLimitPhrases: Set<String> = setOf("rate limit", "rate-limit"),
) : RetryPolicy {

    override fun open(parent: RetryContext?): RetryContext {
        return RetryContextSupport(parent)
    }

    override fun close(context: RetryContext?) {
        // No cleanup needed for this implementation
    }

    override fun registerThrowable(
        context: RetryContext?,
        throwable: Throwable?,
    ) {
        if (context is RetryContextSupport && throwable != null) {
            context.registerThrowable(throwable)
        }
    }

    override fun canRetry(context: RetryContext): Boolean {
        if (context.retryCount == 0) {
            // First attempt, always retry
            return true
        }
        if (context.retryCount >= maxAttempts) {
            return false
        }

        return when (val lastException = context.lastThrowable) {
            // ReplanRequestedException is a control flow signal, not an error to retry
            is ReplanRequestedException -> false

            is TransientAiException -> true
            is NonTransientAiException -> {
                val m = lastException.message ?: return false
                rateLimitPhrases.any { phrase ->
                    m.contains(phrase, ignoreCase = true)
                }
            }

            is IllegalArgumentException -> false

            is IllegalStateException -> false

            is UnsupportedOperationException -> false

            else -> true
        }
    }
}
