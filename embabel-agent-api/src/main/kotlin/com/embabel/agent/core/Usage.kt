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
package com.embabel.agent.core

/**
 * LLM usage data
 */
data class Usage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val nativeUsage: Any?,
) {

    val totalTokens: Int?
        get() = when {
            promptTokens == null && completionTokens == null -> null
            else -> (promptTokens ?: 0) + (completionTokens ?: 0)
        }

    /**
     * Combine two Usage instances by summing their token counts.
     * Used for accumulating usage across multiple LLM calls in a tool loop.
     */
    operator fun plus(other: Usage): Usage = Usage(
        promptTokens = when {
            this.promptTokens == null && other.promptTokens == null -> null
            else -> (this.promptTokens ?: 0) + (other.promptTokens ?: 0)
        },
        completionTokens = when {
            this.completionTokens == null && other.completionTokens == null -> null
            else -> (this.completionTokens ?: 0) + (other.completionTokens ?: 0)
        },
        nativeUsage = null, // Cannot combine native usage objects
    )
}
