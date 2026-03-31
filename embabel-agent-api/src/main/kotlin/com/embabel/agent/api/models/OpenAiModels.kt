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
 * Well-known models from OpenAI.
 * Model IDs verified against GET /v1/models on 2026-03-29.
 * Undated aliases (e.g. GPT_54) always resolve to the latest pinned version.
 * Use dated constants (e.g. GPT_54_2026_03_05) for reproducible behaviour.
 */
class OpenAiModels {

    companion object {

        // =====================================================================
        // GPT-5.4 FAMILY (Current Flagship - March 2026)
        // =====================================================================

        const val GPT_54 = "gpt-5.4"
        const val GPT_54_2026_03_05 = "gpt-5.4-2026-03-05"

        const val GPT_54_MINI = "gpt-5.4-mini"
        const val GPT_54_MINI_2026_03_17 = "gpt-5.4-mini-2026-03-17"

        const val GPT_54_NANO = "gpt-5.4-nano"
        const val GPT_54_NANO_2026_03_17 = "gpt-5.4-nano-2026-03-17"

        const val GPT_54_PRO = "gpt-5.4-pro"
        const val GPT_54_PRO_2026_03_05 = "gpt-5.4-pro-2026-03-05"

        // =====================================================================
        // GPT-5.2 FAMILY (December 2025)
        // =====================================================================

        const val GPT_52 = "gpt-5.2"
        const val GPT_52_2025_12_11 = "gpt-5.2-2025-12-11"

        const val GPT_52_PRO = "gpt-5.2-pro"
        const val GPT_52_PRO_2025_12_11 = "gpt-5.2-pro-2025-12-11"

        // =====================================================================
        // GPT-5.1 FAMILY (November 2025)
        // =====================================================================

        const val GPT_51 = "gpt-5.1"
        const val GPT_51_2025_11_13 = "gpt-5.1-2025-11-13"

        // =====================================================================
        // GPT-5 FAMILY (August 2025)
        // =====================================================================

        const val GPT_5 = "gpt-5"
        const val GPT_5_2025_08_07 = "gpt-5-2025-08-07"

        const val GPT_5_MINI = "gpt-5-mini"
        const val GPT_5_MINI_2025_08_07 = "gpt-5-mini-2025-08-07"

        const val GPT_5_NANO = "gpt-5-nano"
        const val GPT_5_NANO_2025_08_07 = "gpt-5-nano-2025-08-07"

        const val GPT_5_PRO = "gpt-5-pro"
        const val GPT_5_PRO_2025_10_06 = "gpt-5-pro-2025-10-06"

        // =====================================================================
        // GPT-4.1 FAMILY (April 2025 - 1M token context window)
        // =====================================================================

        const val GPT_41 = "gpt-4.1"
        const val GPT_41_2025_04_14 = "gpt-4.1-2025-04-14"

        const val GPT_41_MINI = "gpt-4.1-mini"
        const val GPT_41_MINI_2025_04_14 = "gpt-4.1-mini-2025-04-14"

        const val GPT_41_NANO = "gpt-4.1-nano"
        const val GPT_41_NANO_2025_04_14 = "gpt-4.1-nano-2025-04-14"

        // =====================================================================
        // GPT-4o FAMILY (retained — only models with audio input/output support)
        // =====================================================================

        const val GPT_4O = "gpt-4o"
        const val GPT_4O_MINI = "gpt-4o-mini"

        // =====================================================================
        // EMBEDDING MODELS
        // =====================================================================

        const val TEXT_EMBEDDING_3_LARGE = "text-embedding-3-large"
        const val TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small"
        const val TEXT_EMBEDDING_ADA_002 = "text-embedding-ada-002"

        const val DEFAULT_TEXT_EMBEDDING_MODEL = TEXT_EMBEDDING_3_SMALL

        // =====================================================================
        // PROVIDER
        // =====================================================================

        const val PROVIDER = "OpenAI"
    }
}
