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

import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.spi.support.springai.SpringAiRetryPolicy
import com.embabel.common.util.loggerFor
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.RetryPolicy
import org.springframework.retry.support.RetryTemplate
import java.time.Duration
import org.springframework.core.retry.RetryPolicy as CoreRetryPolicy
import org.springframework.core.retry.RetryTemplate as CoreRetryTemplate

interface RetryTemplateProvider {
    val maxAttempts: Int

    /**
     * Prefix when field values are assigned via configuration.
     */
    val propertyPrefix: String
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
                    // ToolControlFlowSignal exceptions (ReplanRequestedException, UserInputRequiredException, etc.)
                    // are control flow signals, not errors to retry - rethrow to abort retry
                    if (throwable is ToolControlFlowSignal) {
                        throw throwable
                    }
                    // Security denials are deterministic - retrying will never succeed
                    if (throwable.javaClass.name == ACCESS_DENIED_EXCEPTION) {
                        throw throwable
                    }
                    // onError fires before the policy is consulted, so the listener has to reach the
                    // same verdict itself rather than announce a retry that may never happen.
                    val attemptsMade = context.retryCount
                    if (attemptsMade >= maxAttempts || !LlmRetryDecision.isRetryable(throwable)) {
                        loggerFor<RetryProperties>().info(
                            "LLM invocation {} failed on attempt {} of {}, not retrying: {}",
                            name,
                            attemptsMade,
                            maxAttempts,
                            throwable.message,
                            throwable,
                        )
                    } else if (LlmRetryDecision.isRateLimit(throwable)) {
                        loggerFor<RetryProperties>().info(
                            "LLM invocation {} RATE LIMITED: retry attempt {} of {}",
                            name,
                            attemptsMade + 1,
                            maxAttempts,
                        )
                    } else {
                        loggerFor<RetryProperties>().info(
                            "LLM invocation {} retry attempt {} of {} after: {}",
                            name,
                            attemptsMade + 1,
                            maxAttempts,
                            throwable.message,
                            throwable,
                        )
                    }
                }

                override fun <T, E : Throwable> close(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable?,
                ) {
                    // close fires on every failing exit, exhausted or not
                    if (throwable != null && context.retryCount >= maxAttempts) {
                        loggerFor<RetryProperties>().warn(
                            "Maximum attempts of {} have reached. The maximum attempt can be configured using property {}.max-attempts",
                            maxAttempts,
                            propertyPrefix
                        )
                    }
                }
            })
            .build()
    }

    /**
     * Spring Framework 7 template, as taken by the Spring AI 2.0 model builders. Filters on the
     * same [LlmRetryDecision] as [retryTemplate]: with no predicate, core.retry replays every
     * throwable, including a malformed request or a bad API key.
     */
    fun coreRetryTemplate(): CoreRetryTemplate =
        CoreRetryTemplate(
            CoreRetryPolicy.builder()
                .maxRetries((maxAttempts - 1).coerceAtLeast(0).toLong())
                .delay(Duration.ofMillis(backoffMillis))
                .multiplier(backoffMultiplier)
                .maxDelay(Duration.ofMillis(backoffMaxInterval))
                .predicate(LlmRetryDecision::isRetryable)
                .build()
        )

    companion object {
        /** Optional dependency, matched by name so spring-security stays off the classpath. */
        private const val ACCESS_DENIED_EXCEPTION = "org.springframework.security.access.AccessDeniedException"
    }
}
