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
package com.embabel.agent.api.models

/**
 * Provides constants for DeepSeek AI model identifiers.
 * This class contains the latest model versions for DeepSeek AI models offered by DeepSeek.
 */
class DeepSeekModels {

    companion object {

        const val DEEPSEEK_CHAT = "deepseek-chat";
        const val DEEPSEEK_REASONER = "deepseek-reasoner";

        const val PROVIDER = "Deepseek";
    }
}
