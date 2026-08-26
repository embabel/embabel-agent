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
package com.embabel.agent.config.models.googlegenai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.retry.TransientAiException
import org.springframework.core.retry.Retryable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that [GoogleGenAiModelsConfig] honours the retry properties it declares.
 *
 * [GoogleGenAiProperties] implements `RetryProperties` and binds
 * `embabel.agent.platform.models.googlegenai.max-attempts`, so setting it to 1 must leave the chat
 * model with a template that tries once and gives up. Instead the model was handed a bare
 * `RetryTemplate()`, whose `RetryPolicy.withDefaults()` retried every throwable three times over a
 * fixed one-second backoff — four attempts nobody configured, on errors that would never succeed.
 *
 * The template is exercised directly rather than through a call: the Google GenAI SDK brings its
 * own transport, so there is no request factory to intercept, and the retry policy is the whole of
 * what this test is about.
 */
class GoogleGenAiRetryPropertiesTest {

    @Test
    fun `honours the configured max-attempts`() {
        googleGenAiRunner("embabel.agent.platform.models.googlegenai.max-attempts=1")
            .run { context ->
                val retryTemplate = retryTemplateOf(context)
                val attempts = AtomicInteger()
                assertThrows<Throwable> {
                    retryTemplate.execute(Retryable<Any> {
                        attempts.incrementAndGet()
                        throw TransientAiException("upstream unavailable")
                    })
                }

                assertEquals(
                    1, attempts.get(),
                    "max-attempts=1 means a single try and no retry",
                )
            }
    }
}
