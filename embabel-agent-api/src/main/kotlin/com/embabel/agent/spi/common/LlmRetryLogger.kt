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

import com.embabel.common.util.loggerFor

/**
 * What the platform says about a failing LLM call, in one place.
 *
 * spring-retry and the Spring Framework 7 core.retry report a loop through different callbacks, so
 * each template drives these three events from its own listener. Only the wording is shared, which
 * is the point: an operator reads the same lines whichever of the twelve providers failed.
 *
 * Logs under [RetryProperties] so both paths stay greppable under one logger name.
 */
internal class LlmRetryLogger(
    private val name: String,
    private val maxAttempts: Int,
    private val propertyPrefix: String,
) {

    /**
     * A retry that is about to happen. [retryNumber] is 1 for the first retry, so it reads as the
     * attempt the caller is about to make. Only ever call this when a retry really follows.
     */
    fun retrying(retryNumber: Int, cause: Throwable?) {
        if (cause != null && LlmRetryDecision.isRateLimit(cause)) {
            logger.info(
                "LLM invocation {} RATE LIMITED: retry attempt {} of {}",
                name,
                retryNumber,
                maxAttempts,
            )
        } else {
            logger.info(
                "LLM invocation {} retry attempt {} of {} after: {}",
                name,
                retryNumber,
                maxAttempts,
                cause?.message,
                cause,
            )
        }
    }

    /** The loop is giving up, with [attemptsMade] calls behind it, exhausted or not. */
    fun notRetrying(attemptsMade: Int, cause: Throwable?) {
        logger.info(
            "LLM invocation {} failed on attempt {} of {}, not retrying: {}",
            name,
            attemptsMade,
            maxAttempts,
            cause?.message,
            cause,
        )
    }

    /**
     * Only when [maxAttempts] really was reached. Raising the ceiling is the one action this line
     * suggests, so claiming it after a single non-retryable failure sends the operator nowhere.
     *
     * The only WARN of the three, so in production it is often the single line that survives the
     * level filter. It names the call for that reason: the property prefix identifies the provider,
     * not which of its models, ranker or action ran out of attempts.
     */
    fun exhausted() {
        logger.warn(
            "LLM invocation {}: Maximum attempts of {} have been reached. The maximum attempt can be configured using property {}.max-attempts",
            name,
            maxAttempts,
            propertyPrefix,
        )
    }

    private companion object {
        val logger = loggerFor<RetryProperties>()
    }
}
