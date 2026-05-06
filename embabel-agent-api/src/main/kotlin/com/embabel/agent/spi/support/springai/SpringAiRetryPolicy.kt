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

import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.core.NonRetryable
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.Retryable
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
        if (context.retryCount >= maxAttempts) {
            return false
        }

        val lastException = context.lastThrowable ?: return true

        // Check entire exception cause chain for markers
        var current: Throwable? = lastException
        while (current != null) {
            if (current is NonRetryable) {
                return false
            }
            if (current is Retryable) {
                return true
            }
            current = current.cause
        }

        return when (lastException) {
            // Control flow signals - not errors to retry
            is ReplanRequestedException -> false
            is TerminateActionException -> false
            is TerminateAgentException -> false
            is ToolControlFlowSignal -> false  // Catch-all for other control flow signals

            // Spring AI markers
            is TransientAiException -> true

            is NonTransientAiException -> {
                val m = lastException.message ?: return false
                rateLimitPhrases.any { phrase ->
                    m.contains(phrase, ignoreCase = true)
                }
            }

            // Common programming errors that should never be retried
            is IllegalArgumentException -> false
            is IllegalStateException -> false
            is UnsupportedOperationException -> false
            is NullPointerException -> false
            is ClassCastException -> false

            // Default: retry unknown exceptions (backward compatible)
            else -> true
        }
    }
}
