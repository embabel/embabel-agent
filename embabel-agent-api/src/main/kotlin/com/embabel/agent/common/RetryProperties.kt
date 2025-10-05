/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.common

import com.embabel.agent.spi.support.LlmDataBindingProperties.Companion.isRateLimitError
import com.embabel.common.util.loggerFor
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.RetryPolicy
import org.springframework.retry.context.RetryContextSupport
import org.springframework.retry.support.RetryTemplate
import java.time.Duration

interface RetryTemplateProvider {
    val maxAttempts: Int
    fun retryTemplate(name: String): RetryTemplate
}

/**
 * Extended by configuration that needs retry regarding Spring AI.
 */
interface RetryProperties : RetryTemplateProvider {
    val backoffMillis: Long
    val backoffMultiplier: Double
    val backoffMaxInterval: Long

    val retryPolicy: RetryPolicy get() = SpringAiRetryPolicy(maxAttempts)

    override fun retryTemplate(name: String): RetryTemplate {
        return RetryTemplate.builder()
            .exponentialBackoff(
                Duration.ofMillis(backoffMillis),
                backoffMultiplier,
                Duration.ofMillis(backoffMaxInterval)
            )
            .customPolicy(retryPolicy)
            .withListener(object : RetryListener {
                override fun <T, E : Throwable> onError(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable,
                ) {
                    if (isRateLimitError(throwable)) {
                        loggerFor<RetryProperties>().info(
                            "🔒 LLM invocation {} RATE LIMITED: Retry attempt {} of {}",
                            name,
                            context.retryCount,
                            if (retryPolicy.maxAttempts > 0) retryPolicy.maxAttempts else "unknown",
                        )
                        return
                    }
                    loggerFor<RetryProperties>().info(
                        "Operation $name: Retry error. Retry count: ${context.retryCount}",
                        throwable,
                    )
                }
            })
            .build()
    }
}

/**
 * Retry policy for Spring AI operations.
 */
private class SpringAiRetryPolicy(
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
