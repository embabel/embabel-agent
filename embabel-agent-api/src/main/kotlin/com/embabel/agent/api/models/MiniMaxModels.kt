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
 * Provides constants for MiniMax AI model identifiers.
 * MiniMax offers large language models with up to 1M token context windows
 * via an OpenAI-compatible API.
 *
 * @see <a href="https://www.minimax.io">MiniMax AI</a>
 */
class MiniMaxModels {

    companion object {

        const val MINIMAX_M2_7 = "MiniMax-M2.7"
        const val MINIMAX_M2_7_HIGHSPEED = "MiniMax-M2.7-highspeed"

        const val PROVIDER = "MiniMax"
    }
}
