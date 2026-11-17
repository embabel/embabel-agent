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
package com.embabel.common.ai.decision

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.jetbrains.annotations.ApiStatus

/**
 * The tokens one decision request used, as the provider reported them.
 *
 * Every count is optional because providers report different things. A missing count means the
 * provider said nothing about it, and it is left out of JSON.
 *
 * @param inputTokens the tokens read from the request
 * @param outputTokens the tokens written in the reply
 * @param totalTokens the provider's own total
 * @param cacheReadInputTokens the input tokens served from the provider's cache
 * @param cacheWriteInputTokens the input tokens written to the provider's cache
 * @param reasoningTokens the tokens spent on hidden reasoning
 * @throws IllegalArgumentException if any count is negative
 */
@ApiStatus.Experimental
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DecisionUsage @JvmOverloads constructor(
    /** The tokens read from the request, or null when not reported. */
    @param:JsonProperty("inputTokens") @get:JsonProperty("inputTokens")
    val inputTokens: Long? = null,
    /** The tokens written in the reply, or null when not reported. */
    @param:JsonProperty("outputTokens") @get:JsonProperty("outputTokens")
    val outputTokens: Long? = null,
    /** The provider's own total, or null when not reported. */
    @param:JsonProperty("totalTokens") @get:JsonProperty("totalTokens")
    val totalTokens: Long? = null,
    /** The input tokens served from the provider's cache, or null when not reported. */
    @param:JsonProperty("cacheReadInputTokens") @get:JsonProperty("cacheReadInputTokens")
    val cacheReadInputTokens: Long? = null,
    /** The input tokens written to the provider's cache, or null when not reported. */
    @param:JsonProperty("cacheWriteInputTokens") @get:JsonProperty("cacheWriteInputTokens")
    val cacheWriteInputTokens: Long? = null,
    /** The tokens spent on hidden reasoning, or null when not reported. */
    @param:JsonProperty("reasoningTokens") @get:JsonProperty("reasoningTokens")
    val reasoningTokens: Long? = null,
) {

    init {
        requireCount("inputTokens", inputTokens)
        requireCount("outputTokens", outputTokens)
        requireCount("totalTokens", totalTokens)
        requireCount("cacheReadInputTokens", cacheReadInputTokens)
        requireCount("cacheWriteInputTokens", cacheWriteInputTokens)
        requireCount("reasoningTokens", reasoningTokens)
    }

    private fun requireCount(name: String, count: Long?) {
        require(count == null || count >= 0) { "$name must be zero or more, but was $count" }
    }
}
