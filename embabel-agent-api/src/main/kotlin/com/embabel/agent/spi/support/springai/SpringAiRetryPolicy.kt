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

import com.embabel.agent.spi.common.LlmRetryDecision
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryPolicy
import org.springframework.retry.context.RetryContextSupport

/**
 * Retry policy for Spring AI operations.
 */
internal class SpringAiRetryPolicy(
    private val maxAttempts: Int,
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
        if (context.retryCount >= maxAttempts) {
            return false
        }
        val lastException = context.lastThrowable ?: return true
        return LlmRetryDecision.isRetryable(lastException)
    }
}
