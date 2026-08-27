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
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryState
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.RetryPolicy
import org.springframework.retry.support.RetryTemplate
import java.time.Duration
import org.springframework.core.retry.RetryListener as CoreRetryListener
import org.springframework.core.retry.RetryPolicy as CoreRetryPolicy
import org.springframework.core.retry.RetryTemplate as CoreRetryTemplate
import org.springframework.core.retry.Retryable as CoreRetryable

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
        val log = LlmRetryLogger(name, maxAttempts, propertyPrefix)
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
                    // The throwable is registered before the listener runs, so retryCount is already
                    // 1 on the first failure: it numbers the retry that follows, exactly as
                    // core.retry's RetryState does. Both paths must announce the same number.
                    val attemptsMade = context.retryCount
                    if (attemptsMade >= maxAttempts || !LlmRetryDecision.isRetryable(throwable)) {
                        log.notRetrying(attemptsMade, throwable)
                    } else {
                        log.retrying(attemptsMade, throwable)
                    }
                }

                override fun <T, E : Throwable> close(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable?,
                ) {
                    // close fires on every failing exit, exhausted or not
                    if (throwable != null && context.retryCount >= maxAttempts) {
                        log.exhausted()
                    }
                }
            })
            .build()
    }

    /**
     * Spring Framework 7 template, as taken by the Spring AI 2.0 model builders. Filters on the same
     * [LlmRetryDecision] as [retryTemplate] and reports through the same [LlmRetryLogger]: with no
     * predicate core.retry replays every throwable, including a malformed request or a bad API key,
     * and with no listener it does so without a word.
     */
    fun coreRetryTemplate(name: String): CoreRetryTemplate {
        val log = LlmRetryLogger(name, maxAttempts, propertyPrefix)
        // core.retry counts the retries that follow the first call, where maxAttempts counts calls.
        val corePolicy = CoreRetryPolicy.builder()
            .maxRetries((maxAttempts - 1).coerceAtLeast(0).toLong())
            .delay(Duration.ofMillis(backoffMillis))
            .multiplier(backoffMultiplier)
            .maxDelay(Duration.ofMillis(backoffMaxInterval))
            .predicate(LlmRetryDecision::isRetryable)
            .build()
        return CoreRetryTemplate(corePolicy).apply {
            setRetryListener(object : CoreRetryListener {

                /** Fires only when a retry really follows, so there is no verdict to recompute here. */
                override fun beforeRetry(
                    policy: CoreRetryPolicy,
                    retryable: CoreRetryable<*>,
                    state: RetryState,
                ) {
                    log.retrying(state.retryCount, state.lastException)
                }

                /**
                 * Fires on every failing exit, so "exhaustion" is a misnomer: the predicate rejecting
                 * the first failure lands here too. The exceptions it carries are the attempts made.
                 */
                override fun onRetryPolicyExhaustion(
                    policy: CoreRetryPolicy,
                    retryable: CoreRetryable<*>,
                    exception: RetryException,
                ) {
                    val attemptsMade = exception.exceptions.size
                    log.notRetrying(attemptsMade, exception.lastException)
                    if (attemptsMade >= maxAttempts) {
                        log.exhausted()
                    }
                }
            })
        }
    }

    companion object {
        /** Optional dependency, matched by name so spring-security stays off the classpath. */
        private const val ACCESS_DENIED_EXCEPTION = "org.springframework.security.access.AccessDeniedException"
    }
}
