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

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Adapts tool response content to meet provider-specific format requirements
 * before the content is sent to the LLM.
 *
 * Some LLM providers impose constraints on tool response format. For example,
 * Google GenAI requires tool responses (`FunctionResponse.response`) to be valid
 * JSON objects — plain text causes `JsonParseException` or silent data loss.
 * Other providers like OpenAI and Anthropic accept plain text as-is.
 *
 * This interface allows each provider's auto-configuration to supply its own
 * adapter, keeping provider-specific logic out of the shared message conversion
 * infrastructure.
 *
 * Follows the same pattern as [com.embabel.common.ai.model.OptionsConverter]
 * — a per-provider strategy plugged in at configuration time.
 *
 * @see JsonWrappingToolResponseContentAdapter
 */
fun interface ToolResponseContentAdapter {

    /**
     * Adapt tool response content for the target LLM provider.
     *
     * @param content The raw tool response content (may be plain text, JSON, etc.)
     * @return The adapted content suitable for the target provider
     */
    fun adapt(content: String): String

    companion object {

        /**
         * Default adapter that passes content through unchanged.
         * Suitable for providers that accept plain text in tool responses
         * (e.g., OpenAI, Anthropic).
         */
        @JvmField
        val PASSTHROUGH = ToolResponseContentAdapter { it }
    }
}

/**
 * Wraps non-JSON tool response content in a JSON object for providers
 * that require valid JSON in tool responses (e.g., Google GenAI / Gemini).
 *
 * Behavior:
 * - Content that already looks like a JSON object (`{...}`) or array (`[...]`)
 *   is passed through unchanged.
 * - All other content is wrapped as `{"result": "<content>"}`.
 *
 * This acts as a safety net at the provider boundary. Tools are encouraged
 * to return structured JSON directly (see `enabledToolsJson()` in
 * [com.embabel.agent.api.tool.progressive.UnfoldingTool]), but this adapter
 * catches any remaining plain-text responses from arbitrary [com.embabel.agent.api.tool.Tool]
 * implementations.
 *
 * Note: The `startsWith` check is a fast-path heuristic, not full JSON
 * validation. Malformed JSON-like strings (e.g., `{not json}`) will pass
 * through and may fail downstream — this is acceptable since such output
 * is extremely rare from well-behaved tools.
 */
class JsonWrappingToolResponseContentAdapter : ToolResponseContentAdapter {

    private val objectMapper = ObjectMapper()

    override fun adapt(content: String): String {
        val trimmed = content.trimStart()
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            return content
        }
        return objectMapper.writeValueAsString(mapOf("result" to content))
    }
}
