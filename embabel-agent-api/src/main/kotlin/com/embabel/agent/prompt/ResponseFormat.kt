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
package com.embabel.agent.prompt

import com.embabel.common.ai.prompt.PromptContributor

/**
 * PromptContributor that defines the expected response format
 * for text responses
 */
data class ResponseFormat(
    val format: String,
) : PromptContributor {

    override fun contribution(): String =
        """
            # RESPONSE FORMAT #
            $format
        """.trimIndent()

    override val role: String = "response_format"

    companion object {
        val MARKDOWN = ResponseFormat("Markdown")
        val HTML = ResponseFormat("HTML")

    }
}
