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
package com.embabel.agent.config.models

/**
 * Provides constants for Anthropic AI model identifiers.
 * This class contains the latest model versions for Claude AI models offered by Anthropic.
 */
class AnthropicModels {

    companion object {

        const val CLAUDE_37_SONNET = "claude-3-7-sonnet-latest"

        const val CLAUDE_35_HAIKU = "claude-3-5-haiku-latest"

        const val CLAUDE_40_OPUS = "claude-opus-4-20250514"

        const val PROVIDER = "Anthropic"
    }
}
