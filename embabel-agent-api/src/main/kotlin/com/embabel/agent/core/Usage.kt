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

import com.embabel.common.ai.model.NativeUsage
import kotlin.jvm.internal.DefaultConstructorMarker

/**
 * LLM usage data
 * @param promptTokens all input tokens, including any cache reads and writes
 * @param nativeUsage the provider's own usage object, if any; pricing does not read it
 * @param cacheReadInputTokens input tokens read from the provider's prompt cache, if reported
 * @param cacheWriteInputTokens input tokens written to the provider's prompt cache, if reported
 * @param billableUsage every billable token kind, filled by the provider adapter per call; pricing prefers it
 */
data class Usage @JvmOverloads constructor(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val nativeUsage: Any?,
    val cacheReadInputTokens: Int? = null,
    val cacheWriteInputTokens: Int? = null,
    val billableUsage: NativeUsage? = null,
) {

    /**
     * Binary compatibility: the synthetic constructor that Kotlin callers compiled before [billableUsage]
     * link to when they leave arguments out. Bit `i` of [mask] set means argument `i` was omitted.
     */
    @Suppress("UNUSED_PARAMETER")
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(
        promptTokens: Int?, completionTokens: Int?, nativeUsage: Any?, cacheReadInputTokens: Int?,
        cacheWriteInputTokens: Int?, mask: Int, marker: DefaultConstructorMarker?,
    ) : this(
        promptTokens, completionTokens, nativeUsage,
        if (mask and (1 shl 3) != 0) null else cacheReadInputTokens,
        if (mask and (1 shl 4) != 0) null else cacheWriteInputTokens,
    )

    val totalTokens: Int?
        get() = when {
            promptTokens == null && completionTokens == null -> null
            else -> (promptTokens ?: 0) + (completionTokens ?: 0)
        }

    /**
     * Combine two Usage instances by summing their token counts.
     * Used for accumulating usage across multiple LLM calls in a tool loop.
     * If either side has [billableUsage], the other side's is derived from its generic counts.
     */
    operator fun plus(other: Usage): Usage = Usage(
        promptTokens = sum(promptTokens, other.promptTokens),
        completionTokens = sum(completionTokens, other.completionTokens),
        nativeUsage = null, // Cannot combine native usage objects
        cacheReadInputTokens = sum(cacheReadInputTokens, other.cacheReadInputTokens),
        cacheWriteInputTokens = sum(cacheWriteInputTokens, other.cacheWriteInputTokens),
        billableUsage = if (billableUsage == null && other.billableUsage == null) null
            else toBillableUsage() + other.toBillableUsage(),
    )

    /** [billableUsage], or the same counts derived from the generic fields. */
    fun toBillableUsage(): NativeUsage = billableUsage ?: NativeUsage(
        inputTokens = ((promptTokens ?: 0) - (cacheReadInputTokens ?: 0) - (cacheWriteInputTokens ?: 0)).coerceAtLeast(0),
        outputTokens = completionTokens ?: 0,
        cacheReadInputTokens = cacheReadInputTokens ?: 0,
        cacheWriteInputTokens = cacheWriteInputTokens ?: 0,
    )

    private fun sum(a: Int?, b: Int?): Int? =
        if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    /** Binary compatibility: the `copy` callers compiled before the cache fields link to. Keeps them. */
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    fun copy(promptTokens: Int?, completionTokens: Int?, nativeUsage: Any?): Usage =
        copy(promptTokens = promptTokens, completionTokens = completionTokens, nativeUsage = nativeUsage)

    /** Binary compatibility: the `copy` callers compiled before [billableUsage] link to. Keeps it. */
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    fun copy(
        promptTokens: Int?, completionTokens: Int?, nativeUsage: Any?, cacheReadInputTokens: Int?,
        cacheWriteInputTokens: Int?,
    ): Usage = copy(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        nativeUsage = nativeUsage,
        cacheReadInputTokens = cacheReadInputTokens,
        cacheWriteInputTokens = cacheWriteInputTokens,
    )

    companion object {

        /**
         * Binary compatibility: the `copy$default` that Kotlin callers compiled before the cache fields
         * link to. Bit `i` of [mask] set means argument `i` was omitted, so [self]'s value is kept.
         */
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
        fun copyDefault(
            self: Usage, promptTokens: Int?, completionTokens: Int?, nativeUsage: Any?, mask: Int, marker: Any?,
        ): Usage = self.copy(
            promptTokens = if (mask and 1 != 0) self.promptTokens else promptTokens,
            completionTokens = if (mask and 2 != 0) self.completionTokens else completionTokens,
            nativeUsage = if (mask and 4 != 0) self.nativeUsage else nativeUsage,
        )

        /** Binary compatibility: the `copy$default` that Kotlin callers compiled before [billableUsage] link to. */
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
        fun copyDefault(
            self: Usage, promptTokens: Int?, completionTokens: Int?, nativeUsage: Any?, cacheReadInputTokens: Int?,
            cacheWriteInputTokens: Int?, mask: Int, marker: Any?,
        ): Usage = self.copy(
            promptTokens = if (mask and 1 != 0) self.promptTokens else promptTokens,
            completionTokens = if (mask and 2 != 0) self.completionTokens else completionTokens,
            nativeUsage = if (mask and 4 != 0) self.nativeUsage else nativeUsage,
            cacheReadInputTokens = if (mask and 8 != 0) self.cacheReadInputTokens else cacheReadInputTokens,
            cacheWriteInputTokens = if (mask and 16 != 0) self.cacheWriteInputTokens else cacheWriteInputTokens,
        )
    }
}
