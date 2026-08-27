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
package com.embabel.agent.spi.support.springai

import org.springframework.ai.chat.prompt.ChatOptions
import tools.jackson.databind.ObjectMapper

/**
 * Best-effort parse + strip helpers for a one-shot HTTP-layer retry when a model rejects a
 * request field that slipped past capability-aware option conversion.
 *
 * **Primary defence (preferred):** model YAML `special_handling` →
 * [com.embabel.agent.openai.ModelCapabilities] →
 * [com.embabel.agent.openai.CapabilityAwareOpenAiOptionsConverter] **warns and drops**
 * unsupported sampling values **before** the call (same strategy as #1874).
 *
 * **This retry is only a safety net** when a capability flag is missing/incomplete
 * (e.g. YAML lagging a new model restriction). Maintenance cost is deliberately low:
 * only portable [ChatOptions] mutators that [stripParameter] knows how to clear are
 * in scope; unknown field names return null and the original provider error is rethrown.
 * Prefer extending YAML / [com.embabel.agent.openai.ModelCapabilities] over growing this map.
 *
 * **Extraction (OpenAI / Azure via Spring AI):** Spring AI surfaces the HTTP body as the
 * exception message, typically
 * `"400 - { \"error\": { \"param\": \"temperature\", \"code\": \"unsupported_value\", ... } }"`.
 * We parse structured JSON `error.param` (and `error.code` when present) only.
 * Prose-only bodies without `error.param` return null → caller rethrows (fail closed).
 *
 * **Reliability:** not guaranteed for every provider. Non-OpenAI providers rarely share
 * this JSON shape. On mismatch we return null and the caller rethrows.
 */
internal object UnsupportedRequestParameterRetry {

    private val objectMapper = ObjectMapper()

    /**
     * Codes that indicate a request field was rejected and may be stripped for a
     * one-shot retry. Missing code with a present `param` still retries (OpenAI sometimes
     * omits code while setting param).
     */
    private val RETRYABLE_ERROR_CODES = setOf(
        "unsupported_value",
        "unknown_parameter",
        "invalid_value",
    )

    /**
     * Module-internal: used by [InstrumentedChatModel] (same package, different file).
     * Not `private` — Kotlin `private` is file-scoped.
     */
    internal fun extractUnsupportedParameter(throwable: Throwable): String? {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message
            if (message != null) {
                extractFromStructuredOpenAiBody(message)?.let { return it }
            }
            current = current.cause
        }
        return null
    }

    /**
     * Parse Spring AI style `"NNN - {json}"` OpenAI error bodies and return
     * `error.param` when it looks like an unsupported/invalid parameter error.
     */
    private fun extractFromStructuredOpenAiBody(message: String): String? {
        val jsonStart = message.indexOf('{')
        if (jsonStart < 0) return null
        return try {
            val root = objectMapper.readTree(message.substring(jsonStart))
            val error = root.get("error") ?: return null
            val param = error.get("param")?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() }
                ?: return null
            val code = error.get("code")?.takeIf { !it.isNull }?.asString()
            // Fail closed on clearly non-parameter codes when present
            if (code != null && code !in RETRYABLE_ERROR_CODES) {
                return null
            }
            param
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns a copy of [options] with [parameter] cleared, or **null** if the name is not
     * a known portable [ChatOptions] field we can strip safely.
     *
     * Return semantics (important for [InstrumentedChatModel] retry):
     * - **null** = *unknown field name* (e.g. provider-private `seed`) — caller must rethrow;
     *   we never invent options for fields we cannot clear.
     * - **non-null copy** = known field was cleared (even if it was already null). A no-op
     *   strip still allows one harmless retry; it is *not* treated as failure.
     *
     * Scope is deliberately the portable [ChatOptions] surface (temperature, topP,
     * penalties, maxTokens, topK) — the same fields [com.embabel.common.ai.model.LlmOptions]
     * carries. Provider-private fields are not stripped; add a `when` arm only when Spring AI
     * exposes a mutator. This is a safety net, not a product hyperparameter abstraction.
     */
    /**
     * Module-internal: used by [InstrumentedChatModel] (same package, different file).
     * Not `private` — Kotlin `private` is file-scoped.
     */
    internal fun stripParameter(options: ChatOptions, parameter: String): ChatOptions? {
        require(parameter.isNotBlank()) { "parameter name must not be blank" }
        val builder = options.mutate()
        when (normalize(parameter)) {
            "temperature" -> builder.temperature(null)
            "topp" -> builder.topP(null)
            "frequencypenalty" -> builder.frequencyPenalty(null)
            "presencepenalty" -> builder.presencePenalty(null)
            "maxtokens", "maxcompletiontokens" -> builder.maxTokens(null)
            "topk" -> builder.topK(null)
            else -> return null
        }
        return builder.build()
    }

    /**
     * Lowercases and strips separators so `top_p`, `top-p`, and `topP` all become `topp`.
     * After this, a single `when` arm matches every common wire spelling.
     */
    private fun normalize(parameter: String): String =
        parameter.lowercase().replace("_", "").replace("-", "").replace(" ", "")
}
