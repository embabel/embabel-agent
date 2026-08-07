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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import org.opentest4j.TestAbortedException

/**
 * Shared plumbing for the integration tests that call the real OpenAI API.
 *
 * A key that works is not a key entitled to every model: access is granted per project, and the
 * pro tiers routinely are not. Left unhandled that reads as a broken build rather than an
 * unentitled project, so it is skipped rather than failed — and only for that one reason, so a
 * genuine breakage still fails.
 */
object OpenAiIntegrationSupport {

    /** Short and unambiguous: any drift in the answer is the model's, not the prompt's. */
    const val READY_PROMPT = "Reply with exactly the word READY."

    /**
     * Calls [model] with [READY_PROMPT], skipping the test if the project cannot reach it.
     *
     * @param options extra request options, e.g. a token limit
     */
    fun sayReady(ai: Ai, model: String, options: LlmOptions = LlmOptions.withModel(model)): String =
        try {
            ai.withLlm(options).generateText(READY_PROMPT).trim()
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException(
                    "OPENAI_API_KEY is set, but the configured OpenAI project has no access to $model",
                    ex,
                )
            }
            throw ex
        }

    /** The whole cause chain is searched: the provider error arrives wrapped by the retry layer. */
    fun isModelAccessError(ex: Exception): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")

        return message.contains("does not have access to model", ignoreCase = true) ||
            message.contains("model_not_found", ignoreCase = true)
    }
}
