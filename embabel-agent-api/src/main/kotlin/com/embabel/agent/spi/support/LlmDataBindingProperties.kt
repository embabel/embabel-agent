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
package com.embabel.agent.spi.support

import com.embabel.agent.common.RetryTemplateProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.support.RetryTemplate
import java.time.Duration

/**
 * We want to be more forgiving with data binding. This
 * can be important for smaller models.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.llm-operations.data-binding")
class LlmDataBindingProperties : RetryTemplateProvider {
    private val logger = LoggerFactory.getLogger(LlmDataBindingProperties::class.java)

    /**
     * Maximum retry attempts for data binding
     */
    override var maxAttempts: Int = 10

    /**
     * Fixed backoff time in milliseconds between retries
     */
    var fixedBackoffMillis: Long = 30L

    override fun retryTemplate(name: String): RetryTemplate {
        return RetryTemplate.builder()
            .maxAttempts(maxAttempts)
            .fixedBackoff(Duration.ofMillis(fixedBackoffMillis))
            .withListener(object : RetryListener {
                override fun <T : Any, E : Throwable> onError(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable,
                ) {
                    if (isRateLimitError(throwable)) {
                        logger.info(
                            "🔒 LLM invocation {} RATE LIMITED: Retry attempt {} of {}",
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
                            throwable.message ?: "Unknown error"
                        )
                    }
                }
            })
            .build()
    }

    companion object {
        private val RATE_LIMIT_PATTERNS = listOf(
            "rate limit",
            "too many requests",
            "quota exceeded",
            "rate-limited",
            "429",
        )

        fun isRateLimitError(t: Throwable): Boolean {
            val message = t.message?.lowercase() ?: return false
            return RATE_LIMIT_PATTERNS.any { pattern ->
                message.contains(pattern)
            }
        }
    }
}
