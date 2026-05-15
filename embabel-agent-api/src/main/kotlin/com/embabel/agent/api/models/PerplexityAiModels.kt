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
package com.embabel.agent.api.models

class PerplexityAiModels {

    companion object {

        const val PROVIDER = "Perplexity AI"
        // Values are taken from https://docs.perplexity.ai/docs/agent-api/models.
        // Perplexity Models.
        const val SONAR = "perplexity/sonar"
        // Supported 3rd party models as of May 5th 2026.

        // Anthropic models.
        const val ANTHROPIC_CLAUDE_OPUS_4_7 = "anthropic/claude-opus-4-7"
        const val ANTHROPIC_CLAUDE_OPUS_4_6 = "anthropic/claude-opus-4-6"
        const val ANTHROPIC_CLAUDE_OPUS_4_5 = "anthropic/claude-opus-4-5"
        const val ANTHROPIC_CLAUDE_SONNET_4_6 = "anthropic/claude-sonnet-4-6"
        const val ANTHROPIC_CLAUDE_SONNET_4_5 = "anthropic/claude-sonnet-4-5"
        const val ANTHROPIC_CLAUDE_HAIKU_4_5 = "anthropic/claude-haiku-4-5"

        // OpenAI models.
        const val OPEN_AI_GPT_5_5 = "openai/gpt-5.5"
        const val OPEN_AI_GPT_5_4 = "openai/gpt-5.4"
        const val OPEN_AI_GPT_5_4_MINI = "openai/gpt-5.4-mini"
        const val OPEN_AI_GPT_5_4_NANO = "openai/gpt-5.4-nano"
        const val OPEN_AI_GPT_5_2 = "openai/gpt-5.2"
        const val OPEN_AI_GPT_5_1 = "openai/gpt-5.1"
        const val OPEN_AI_GPT_5 = "openai/gpt-5"
        const val OPEN_AI_GPT_5_MINI = "openai/gpt-5-mini"

        // Google Gemini models.
        const val GOOGLE_GEMINI_3_1_PRO = "google/gemini-3.1-pro-preview"
        const val GOOGLE_GEMINI_3_FLASH = "google/gemini-3-flash-preview"

        // NVIDIA models.
        const val NVIDIA_NEMOTRON_3_SUPER_120B = "nvidia/nemotron-3-super-120b-a12b"

        // XAI models
        const val xAI_GROK_4_3 = "xai/grok-4.3"
        const val xAI_GROK_4_2_REASONING = "xai/grok-4.20-reasoning"
        const val xAI_GROK_4_2_NON_REASONING = "xai/grok-4.20-non-reasoning"
        const val xAI_GROK_4_2_MULTI_AGENT = "xai/grok-4.20-multi-agent"
        const val xAI_GROK_4_1_FAST_NON_REASONING = "xai/grok-4-1-fast-non-reasoning"
    }
}
