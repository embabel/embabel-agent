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
package com.embabel.common.ai.converters

import org.jetbrains.annotations.ApiStatus

/** Normalizes a JSON reply without extracting fragments or repairing its content. */
@ApiStatus.Experimental
object JsonResponseText {
    private val codeFence = Regex("""\A\s*```(?:json)?[ \t]*\r?\n(.*?)\r?\n?```\s*\z""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Unwraps one Markdown fence covering the whole reply, with an optional `json` tag.
     * Prose around a fence, other language tags and multiple or nested fences remain unchanged,
     * so a strict JSON parser can reject them. The content is not otherwise trimmed or validated.
     *
     * @param raw the complete model reply
     * @return the single fence's content, or the original reply when it does not match
     */
    @JvmStatic
    fun withoutCodeFence(raw: String): String {
        val content = codeFence.matchEntire(raw)?.groupValues?.get(1) ?: return raw
        return if (content.contains("```")) raw else content
    }
}
