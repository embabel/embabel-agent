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
 * Controls the names published for tools.
 *
 * [LEGACY_NAME_ONLY] preserves `search`.
 * [FULLY_QUALIFIED] qualifies the tool name with its owning agent, so `AgentA` and `search`
 * become `AgentA-search`.
 */
enum class ToolNamingStrategy {
    /** Preserve the existing tool name. */
    LEGACY_NAME_ONLY,

    /** Qualify the tool name with its complete Embabel owner name. */
    FULLY_QUALIFIED,
    ;

    /**
     * Return the published name for a tool with the given owner name.
     *
     * Tool names must match `[a-zA-Z0-9_-]` and not exceed [MAX_NAME_LENGTH].
     * Under [FULLY_QUALIFIED], an owner that is present and not blank is joined with the tool name
     * using `-`. An owner that is null or blank leaves the tool name unqualified.
     *
     * Throws [IllegalArgumentException] if the tool name contains invalid characters or if the
     * published name exceeds [MAX_NAME_LENGTH].
     */
    fun nameFor(
        ownerName: String?,
        toolName: String,
    ): String {
        require(toolName.matches(TOOL_NAME_REGEX)) {
            "Invalid tool name '$toolName': must match [a-zA-Z0-9_-]"
        }
        val prefix = ownerName?.takeIf { it.isNotBlank() }?.let { sanitizeOwner(it) }
        if (prefix != null && toolName.startsWith("$prefix-")) {
            return toolName
        }
        val published = when (this) {
            LEGACY_NAME_ONLY -> toolName
            FULLY_QUALIFIED -> {
                if (prefix != null) "$prefix-$toolName" else toolName
            }
        }
        require(published.length <= MAX_NAME_LENGTH) {
            "Published tool name '$published' exceeds maximum length of $MAX_NAME_LENGTH characters"
        }
        return published
    }

    private fun sanitizeOwner(owner: String): String = buildString {
        owner.forEach { character ->
            if (character.isAsciiLetterOrDigit()) {
                append(character)
            } else {
                append('_').append(character.code.toString(16)).append('_')
            }
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    companion object {
        const val MAX_NAME_LENGTH = 64
        private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")
    }
}
