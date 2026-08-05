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
 * Provides constants for Alibaba Cloud DashScope model identifiers.
 * DashScope offers the Qwen family of large language models via an OpenAI-compatible API,
 * including reasoning-capable models and cost-effective lightweight variants.
 *
 * @see <a href="https://modelstudio.console.alibabacloud.com">DashScope Model Studio</a>
 * @since 1.5.0
 */
class DashScopeModels {

    companion object {

        const val QWEN3_7_MAX = "qwen3.7-max"
        const val QWEN3_7_PLUS = "qwen3.7-plus"
        const val QWEN3_7_FLASH = "qwen3.7-flash"

        const val PROVIDER = "DashScope"
    }
}
