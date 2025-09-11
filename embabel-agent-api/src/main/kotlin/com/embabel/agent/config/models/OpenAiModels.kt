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
 * Well-known models from OpenAI.
 */
class OpenAiModels {

    companion object {

        const val GPT_41_MINI = "gpt-4.1-mini"

        const val GPT_41 = "gpt-4.1"

        const val GPT_41_NANO = "gpt-4.1-nano"

        const val GPT_5 = "gpt-5"

        const val GPT_5_MINI = "gpt-5-mini"

        const val GPT_5_NANO = "gpt-5-nano"

        const val PROVIDER = "OpenAI"

        const val TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small"

        const val DEFAULT_TEXT_EMBEDDING_MODEL = TEXT_EMBEDDING_3_SMALL
    }
}
