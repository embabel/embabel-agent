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

import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.support.LlmDataBindingProperties.Companion.isRateLimitError
import com.embabel.agent.spi.support.springai.SpringAiRetryPolicy
import com.embabel.common.util.loggerFor
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.RetryPolicy
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
                    // ReplanRequestedException is a control flow signal, not an error
                    if (throwable is ReplanRequestedException) {
                        return
                    }
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
