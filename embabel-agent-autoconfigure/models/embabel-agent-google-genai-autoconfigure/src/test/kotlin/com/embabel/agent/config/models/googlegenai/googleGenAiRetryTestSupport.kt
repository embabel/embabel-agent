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
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.retry.RetryTemplate

/**
 * The Google GenAI SDK brings its own transport, so there is no request factory to intercept and no
 * base URL to point at a local server. The retry tests therefore reach for the template the chat
 * model was built with and drive it directly.
 */
internal fun retryTemplateOf(context: AssertableApplicationContext): RetryTemplate {
    val chatModel = context.getBeansOfType(SpringAiLlmService::class.java).values
        .firstOrNull()
        ?.chatModel as? GoogleGenAiChatModel
        ?: throw AssertionError("no Google GenAI chat model registered")
    return GoogleGenAiChatModel::class.java
        .getDeclaredField("retryTemplate")
        .apply { isAccessible = true }
        .get(chatModel) as RetryTemplate
}

internal fun googleGenAiRunner(vararg properties: String): ApplicationContextRunner =
    ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AgentGoogleGenAiAutoConfiguration::class.java))
        .withPropertyValues(
            "GOOGLE_API_KEY=test-key",
            "embabel.agent.platform.models.googlegenai.api-key=test-key",
            *properties,
        )
