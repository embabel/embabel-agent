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

import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.retry.RetryTemplate
import org.springframework.core.retry.Retryable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that [GoogleGenAiModelsConfig] honours the retry properties it declares.
 *
 * [GoogleGenAiProperties] implements `RetryProperties` and binds
 * `embabel.agent.platform.models.googlegenai.max-attempts`, so setting it to 1 must leave the chat
 * model with a template that tries once and gives up. Instead the model is handed a bare
 * `RetryTemplate()`, whose `RetryPolicy.withDefaults()` retries every throwable three times over a
 * fixed one-second backoff — four attempts nobody configured, on errors that will never succeed.
 *
 * The template is exercised directly rather than through a call: the Google GenAI SDK brings its
 * own transport, so there is no request factory to intercept, and the retry policy is the whole of
 * what this test is about.
 */
class GoogleGenAiRetryPropertiesTest {

    @Test
    fun `honours the configured max-attempts`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentGoogleGenAiAutoConfiguration::class.java))
            .withPropertyValues(
                "GOOGLE_API_KEY=test-key",
                "embabel.agent.platform.models.googlegenai.api-key=test-key",
                "embabel.agent.platform.models.googlegenai.max-attempts=1",
            )
            .run { context ->
                val chatModel = context.getBeansOfType(SpringAiLlmService::class.java).values
                    .firstOrNull()
                    ?.chatModel as? GoogleGenAiChatModel
                    ?: throw AssertionError("no Google GenAI chat model registered")

                val retryTemplate = GoogleGenAiChatModel::class.java
                    .getDeclaredField("retryTemplate")
                    .apply { isAccessible = true }
                    .get(chatModel) as RetryTemplate

                val attempts = AtomicInteger()
                assertThrows<Throwable> {
                    retryTemplate.execute(Retryable<Any> {
                        attempts.incrementAndGet()
                        throw IllegalStateException("transport down")
                    })
                }

                assertEquals(
                    1, attempts.get(),
                    "max-attempts=1 means a single try and no retry",
                )
            }
    }
}
