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
package com.embabel.agent.prompt.persona

import com.embabel.common.ai.prompt.PromptContributor


/**
 * CO-STAR prompt framework
 * See https://towardsdatascience.com/how-i-won-singapores-gpt-4-prompt-engineering-competition-34c195a93d41/
 */
data class CoStar @JvmOverloads constructor(
    val context: String,
    val objective: String,
    val style: String,
    val tone: String,
    val audience: String,
    val response: String = "Markdown",
    private val separator: String = "#".repeat(12),
) : PromptContributor {

    override fun contribution() = """
            # CONTEXT #
            $context
            $separator
            # OBJECTIVE #
            $objective
            $separator
            # STYLE #
            $style
            $separator
            # TONE #
            $tone
            $separator
            # AUDIENCE #
            $audience
            $separator
            # RESPONSE FORMAT #
            $response
            $separator
        """.trimIndent()

}
