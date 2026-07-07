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
 * Provides constants for Z.ai (Zhipu AI) GLM model identifiers.
 * Z.ai offers the GLM family of large language models, including models with
 * up to 1M token context windows, via an OpenAI-compatible API.
 *
 * @see <a href="https://z.ai">Z.ai</a>
 */
class ZaiModels {

    companion object {

        const val GLM_5_2 = "glm-5.2"
        const val GLM_4_7 = "glm-4.7"
        const val GLM_4_6 = "glm-4.6"
        const val GLM_4_5_AIR = "glm-4.5-air"
        const val GLM_4_7_FLASH = "glm-4.7-flash"

        const val PROVIDER = "Z.ai"
    }
}
