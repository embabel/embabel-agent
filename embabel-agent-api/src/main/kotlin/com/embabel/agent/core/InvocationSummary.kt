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
 * Shared formatter for invocation summaries (LLM, embeddings, etc.).
 * Single source of truth so subtypes don't drift in formatting.
 */
internal fun formatInvocationSummary(
    label: String,
    modelNames: List<String>,
    callCount: Int,
    promptTokens: Int,
    completionTokens: Int?,
    cost: Double,
    verbose: Boolean,
): String {
    val costStr = "%.4f".format(cost)
    val promptStr = "%,d".format(promptTokens)
    return if (verbose) {
        val lines = mutableListOf(
            "$label used: $modelNames across $callCount calls",
            "Prompt tokens: $promptStr",
        )
        if (completionTokens != null) {
            lines += "Completion tokens: ${"%,d".format(completionTokens)}"
        }
        lines += "Cost: $$costStr"
        lines.joinToString("\n", postfix = "\n")
    } else {
        val parts = mutableListOf(
            "$label: $modelNames across $callCount calls",
            "prompt tokens: $promptStr",
        )
        if (completionTokens != null) {
            parts += "completion tokens: ${"%,d".format(completionTokens)}"
        }
        parts += "cost: $$costStr"
        parts.joinToString("; ")
    }
}
