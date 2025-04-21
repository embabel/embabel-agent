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
package com.embabel.common.ai.model

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "Options for the LLM to use to respond",
)
@JsonDeserialize(`as` = SimpleLlmOptions::class)
interface LlmOptions {

    @get:Schema(
        description = "If provided, custom selection criteria for the LLM to use to answer the question. If not provided, the default LLM (best) will be used.",
        required = false,
    )
    val llmSelectionCriteria: ModelSelectionCriteria?

    @get:Schema(
        description = "The temperature to use when generating responses",
        minimum = "0.0",
        maximum = "1.5",
        defaultValue = "0.0",
        required = true,
    )
    val temperature: Double

}

data class SimpleLlmOptions(
    override val llmSelectionCriteria: ModelSelectionCriteria? = null,
    override val temperature: Double = 0.0,
) : LlmOptions
