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

import java.security.MessageDigest

/**
 * Controls the names published for tools.
 *
 * [LEGACY_NAME_ONLY] preserves `search`.
 * [FULLY_QUALIFIED] publishes the owner and tool name, for example
 * `AgentA.run` and `search` become `AgentA_2e_run-search`. A tool name that the owner already
 * ends with is not repeated, so `AgentA.run` and `run` become `AgentA_2e_run`.
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
     * Under [FULLY_QUALIFIED] the owner and the tool name are encoded separately and joined with `-`.
     * The owner is dropped when it would repeat what the tool name already says, and the tool name is
     * dropped when the owner already ends with it, so neither element appears twice.
     */
    fun nameFor(
        ownerName: String?,
        toolName: String,
    ): String = when (this) {
        LEGACY_NAME_ONLY -> toolName
        FULLY_QUALIFIED -> {
            val owner = ownerName?.takeIf { it.isNotBlank() }
            val parts = when {
                owner == null -> listOf(toolName)
                toolName == owner || toolName.startsWith("$owner.") -> listOf(toolName)
                owner.endsWith(".$toolName") -> listOf(owner)
                else -> listOf(owner, toolName)
            }
            bound(parts.joinToString("-") { sanitize(it) }, parts.joinToString("\u0000"))
        }
    }

    /**
     * Keep a published name within [MAX_NAME_LENGTH], the shortest limit tool-calling providers impose.
     * An over-long name is truncated and given a hash of [source], the unencoded parts, so that two
     * names sharing a truncated prefix still differ.
     */
    private fun bound(name: String, source: String): String {
        if (name.length <= MAX_NAME_LENGTH) return name
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }
        return "${name.take(MAX_NAME_LENGTH - hash.length - 1)}_$hash"
    }

    private fun sanitize(name: String): String = buildString {
        name.forEach { character ->
            if (character.isAsciiLetterOrDigit()) {
                append(character)
            } else {
                append('_').append(character.code.toString(16)).append('_')
            }
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private companion object {
        const val MAX_NAME_LENGTH = 64
        const val HASH_BYTES = 6
    }
}
