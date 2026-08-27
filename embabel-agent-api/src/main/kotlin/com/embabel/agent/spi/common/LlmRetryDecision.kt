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

import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.core.NonRetryable
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.Retryable
import com.embabel.agent.spi.support.LlmDataBindingProperties
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.web.client.RestClientResponseException

/**
 * Single source of truth for how an LLM failure is classified. Shared by the spring-retry policy,
 * the Spring Framework 7 retry template and the retry logging, so all three reach the same verdict.
 */
internal object LlmRetryDecision {

    private const val TOO_MANY_REQUESTS = 429

    /**
     * Last resort, not a registry of provider wording. [httpStatus] runs first and settles every
     * provider that goes through Spring AI's HTTP layer: either a `RestClientResponseException` in
     * the cause chain, or the "<status> - <body>" string Spring AI renders. These phrases are
     * reached only when neither is present, which leaves one case — a native SDK that wraps its own
     * rate limit in its own exception type.
     *
     * Separators are normalised before matching, so this single "rate limit" entry also covers
     * "rate-limit", "rate_limit_error" and "rate_limit_exceeded".
     *
     * Maintenance is the per-provider retry test, not this list. Each provider module ships a
     * hermetic test that replays its real 429 body (`MistralAiRetryTest`, `DeepSeekRetryTest`,
     * `GoogleGenAiRetryTest`), so a gap fails a build rather than surfacing in production. Add a
     * phrase here only alongside a failing test proving no existing entry reaches it.
     */
    private val RATE_LIMIT_PHRASES = listOf("rate limit", "too many requests", "quota exceeded")

    private val SEPARATORS = Regex("""[_\-]+""")

    /**
     * Spring AI renders an HTTP failure as "<status> - <body>", so the status is always a prefix.
     * Matching it anywhere in the message would treat a token count or a request id as a status.
     */
    private val STATUS_PREFIX = Regex("""^\s*(\d{3})\s*-""")

    fun isRateLimit(t: Throwable): Boolean {
        httpStatus(t)?.let { return it == TOO_MANY_REQUESTS }
        val message = t.message?.lowercase()?.replace(SEPARATORS, " ") ?: return false
        return RATE_LIMIT_PHRASES.any { message.contains(it) }
    }

    fun isRetryable(t: Throwable): Boolean {
        marker(t)?.let { return it }
        if (LlmDataBindingProperties.hasNonRetryableDatabindException(t)) {
            return false
        }
        return when (t) {
            is ReplanRequestedException,
            is TerminateActionException,
            is TerminateAgentException,
            is ToolControlFlowSignal,
                -> false

            is TransientAiException -> true
            is NonTransientAiException -> isRateLimit(t)

            is IllegalArgumentException,
            is IllegalStateException,
            is UnsupportedOperationException,
            is NullPointerException,
            is ClassCastException,
                -> false

            else -> true
        }
    }

    /** A [Retryable] or [NonRetryable] marker anywhere in the cause chain overrides every other rule. */
    private fun marker(t: Throwable): Boolean? {
        var cause: Throwable? = t
        while (cause != null) {
            when (cause) {
                is NonRetryable -> return false
                is Retryable -> return true
            }
            cause = cause.cause
        }
        return null
    }

    private fun httpStatus(t: Throwable): Int? {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is RestClientResponseException) return cause.statusCode.value()
            STATUS_PREFIX.find(cause.message ?: "")?.let { return it.groupValues[1].toInt() }
            cause = cause.cause
        }
        return null
    }
}
