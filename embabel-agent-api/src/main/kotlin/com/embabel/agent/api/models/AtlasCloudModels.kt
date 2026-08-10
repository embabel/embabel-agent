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
 * Provides constants for Atlas Cloud model identifiers.
 * Atlas Cloud exposes models from multiple vendors through an OpenAI-compatible API.
 *
 * @see <a href="https://www.atlascloud.ai/models">Atlas Cloud Models</a>
 */
class AtlasCloudModels {

    companion object {

        const val QWEN3_5_FLASH = "qwen/qwen3.5-flash"
        const val QWEN3_8_MAX = "qwen/qwen3.8-max"

        const val PROVIDER = "Atlas Cloud"
    }
}
