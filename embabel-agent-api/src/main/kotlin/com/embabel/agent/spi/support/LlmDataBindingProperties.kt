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
package com.embabel.agent.spi.support

import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.common.LlmRetryDecision
import com.embabel.agent.spi.common.RetryTemplateProvider
import com.embabel.agent.spi.support.LlmDataBindingProperties.Companion.PREFIX
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.support.RetryTemplate
import tools.jackson.databind.DatabindException
import tools.jackson.databind.exc.MismatchedInputException
import java.time.Duration

/**
 * We want to be more forgiving with data binding. This
 * can be important for smaller models.
 * @param maxAttempts Maximum retry attempts for data binding
 * @param fixedBackoffMillis Fixed backoff time in milliseconds between retries
 * @param sendValidationInfo Should we send validation info to the LLM in every request,
 * even before a validation error occurs?
 */
@ConfigurationProperties(prefix = PREFIX)
class LlmDataBindingProperties(
    override val maxAttempts: Int = 10,
    val fixedBackoffMillis: Long = 30L,
    val sendValidationInfo: Boolean = true,
    override val propertyPrefix: String = PREFIX,
) : RetryTemplateProvider {

    private val logger = LoggerFactory.getLogger(LlmDataBindingProperties::class.java)

    override fun retryTemplate(name: String): RetryTemplate {
        return RetryTemplate.builder()
            .maxAttempts(maxAttempts)
            .fixedBackoff(Duration.ofMillis(fixedBackoffMillis))
            // ReplanRequestedException is a control flow signal, not an error to retry
            .notRetryOn(ReplanRequestedException::class.java)
            // GuardRailViolationException is a policy decision, not a transient error to retry
            .notRetryOn(GuardRailViolationException::class.java)
            .withListener(object : RetryListener {
                override fun <T : Any, E : Throwable> onError(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable,
                ) {
                    // ToolControlFlowSignal exceptions (ReplanRequestedException, UserInputRequiredException, etc.)
                    // are control flow signals, not errors to retry - rethrow to abort retry
                    if (throwable is ToolControlFlowSignal) {
                        throw throwable
                    }
                    if (hasNonRetryableDatabindException(throwable)) throw throwable
                    if (isRateLimitError(throwable)) {
                        logger.info(
                            "LLM invocation {} RATE LIMITED: Retry attempt {} of {}",
                            name,
                            context.retryCount,
                            maxAttempts,
                        )
                    } else {
                        logger.warn(
                            "LLM invocation {}: Retry attempt {} of {} due to: {}",
                            name,
                            context.retryCount,
                            maxAttempts,
                            throwable.message ?: "Unknown error",
                        )
                    }
                }
                override fun <T: Any, E : Throwable> close(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable?,
                ) {
                    throwable?.let {
                        if (context.retryCount >= maxAttempts) {
                            logger.warn(
                                "Maximum attempts of {} have reached. The maximum attempt can be configured using property {}.max-attempts",
                                maxAttempts,
                                propertyPrefix
                            )
                        }
                    }
                }
            })
            .build()
    }

    companion object {
        const val PREFIX  = "embabel.agent.platform.llm-operations.data-binding"

        fun isRateLimitError(t: Throwable): Boolean = LlmRetryDecision.isRateLimit(t)

        /**
         * Walks the full exception cause chain looking for a DatabindException that is NOT
         * a MismatchedInputException. Such exceptions indicate Jackson config/annotation errors
         * (e.g. wrong @JsonDeserialize target) — infrastructure bugs, not transient LLM output
         * quality issues. MismatchedInputException (LLM omitted a required field) IS retryable
         * and must be excluded.
         *
         * Note: DatabindException may be wrapped inside RuntimeException by JacksonOutputConverter
         * before reaching the retry layer, so a full cause-chain walk is required rather than a
         * simple instanceof check.
         */
        internal fun hasNonRetryableDatabindException(throwable: Throwable): Boolean {
            var cause: Throwable? = throwable
            while (cause != null) {
                if (cause is DatabindException && cause !is MismatchedInputException) return true
                cause = cause.cause
            }
            return false
        }
    }
}
