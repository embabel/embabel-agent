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
 * [LEGACY_NAME_ONLY] preserves a tool name such as `search` as `search`.
 * [FULL_HIERARCHY] prefixes it with the sanitized owner hierarchy: `AgentA.run` and
 * `search` become `AgentA_run_search`, while `Outer.Inner` and `search` become
 * `Outer_Inner_search`. These are the exposed tool names, not full JVM signatures.
 */
enum class ToolNamingStrategy {
    /** Preserve only the existing tool name. */
    LEGACY_NAME_ONLY,

    /** Prefix the tool name with its stable owner hierarchy. */
    FULL_HIERARCHY,
    ;

    /**
     * Return the published name for a tool owned by [toolConsumer].
     */
    fun nameFor(
        toolConsumer: ToolConsumer,
        toolName: String,
    ): String = when (this) {
        LEGACY_NAME_ONLY -> toolName
        FULL_HIERARCHY -> nameFor(toolConsumer.fullHierarchyName(), toolName)
    }

    /**
     * Return the published name for a generated tool with the given owner hierarchy.
     */
    fun nameFor(
        ownerHierarchy: String?,
        toolName: String,
    ): String = when (this) {
        LEGACY_NAME_ONLY -> toolName
        FULL_HIERARCHY -> listOfNotNull(
            ownerHierarchy?.takeIf { it.isNotBlank() },
            toolName,
        ).joinToString("_") { sanitize(it) }
    }

    private fun sanitize(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_")

    private companion object {
        val UNSAFE_NAME_CHARS = Regex("[^a-zA-Z0-9_]")
    }
}
