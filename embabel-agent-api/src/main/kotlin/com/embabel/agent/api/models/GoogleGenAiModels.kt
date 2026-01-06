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

/**
 * Provides constants for Google GenAI (Gemini) model identifiers.
 * This class contains the latest model versions for Gemini models offered by Google.
 *
 * Uses native Spring AI Google GenAI support (spring-ai-google-genai).
 */
class GoogleGenAiModels {

    companion object {

        // Gemini 3 Family (Preview - Latest Generation)
        const val GEMINI_3_PRO_PREVIEW = "gemini-3-pro-preview"
        const val GEMINI_3_FLASH_PREVIEW = "gemini-3-flash-preview"

        // Gemini 2.5 Family (Stable - Current Generation)
        const val GEMINI_2_5_PRO = "gemini-2.5-pro"
        const val GEMINI_2_5_FLASH = "gemini-2.5-flash"
        const val GEMINI_2_5_FLASH_LITE = "gemini-2.5-flash-lite"

        // Gemini 2.0 Family (Previous Generation)
        const val GEMINI_2_0_FLASH = "gemini-2.0-flash"
        const val GEMINI_2_0_FLASH_LITE = "gemini-2.0-flash-lite"

        // Embedding Models
        const val GEMINI_EMBEDDING_001 = "gemini-embedding-001"
        const val TEXT_EMBEDDING_005 = "text-embedding-005"
        const val TEXT_EMBEDDING_004 = "text-embedding-004"
        const val DEFAULT_TEXT_EMBEDDING_MODEL = TEXT_EMBEDDING_004

        const val PROVIDER = "GoogleGenAI"
    }
}
