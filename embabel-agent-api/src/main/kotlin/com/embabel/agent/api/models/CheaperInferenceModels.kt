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
 * Provides constants for Cheaper Inference model identifiers.
 * Cheaper Inference exposes models from multiple vendors through an OpenAI-compatible API.
 *
 * @see <a href="https://cheaperinference.com/markets">Cheaper Inference Models</a>
 */
class CheaperInferenceModels {

    companion object {

        const val GPT_5_4_MINI = "gpt-5.4-mini"
        const val GPT_5_4 = "gpt-5.4"

        const val PROVIDER = "Cheaper Inference"
    }
}
