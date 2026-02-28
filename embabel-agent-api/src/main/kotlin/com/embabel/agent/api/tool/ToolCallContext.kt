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
package com.embabel.agent.api.tool

/**
 * Framework-agnostic, immutable context passed to tools at call time.
 * Carries out-of-band metadata such as auth tokens, tenant IDs, or
 * correlation IDs without polluting the tool's JSON input schema.
 *
 * Thread-local holder ([current], [set], [remove]) follows the same
 * pattern as [com.embabel.agent.core.AgentProcess] to support
 * decorator chains that only override [Tool.call] (String).
 */
class ToolCallContext private constructor(
    private val entries: Map<String, Any>,
) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Retrieve a value by key, cast to the expected type.
     * Returns `null` when the key is absent.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = entries[key] as T?

    /**
     * Retrieve a value by key, returning [default] when absent.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrDefault(key: String, default: T): T =
        (entries[key] as T?) ?: default

    /**
     * Check whether the context contains a given key.
     * Supports Kotlin `in` operator: `"token" in ctx`.
     */
    operator fun contains(key: String): Boolean = key in entries

    /**
     * Merge this context with [other]. Values in [other] win on conflict.
     */
    fun merge(other: ToolCallContext): ToolCallContext {
        if (other.isEmpty) return this
        if (this.isEmpty) return other
        return ToolCallContext(this.entries + other.entries)
    }

    /**
     * Snapshot as an unmodifiable map, safe to hand to Spring AI [org.springframework.ai.chat.model.ToolContext].
     */
    fun toMap(): Map<String, Any> = entries.toMap()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolCallContext) return false
        return entries == other.entries
    }

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "ToolCallContext($entries)"

    companion object {

        @JvmField
        val EMPTY = ToolCallContext(emptyMap())

        private val holder = ThreadLocal<ToolCallContext>()

        // ---- Factory methods ----

        @JvmStatic
        fun of(entries: Map<String, Any>): ToolCallContext {
            if (entries.isEmpty()) return EMPTY
            return ToolCallContext(entries.toMap())
        }

        fun of(vararg pairs: Pair<String, Any>): ToolCallContext {
            if (pairs.isEmpty()) return EMPTY
            return ToolCallContext(pairs.toMap())
        }

        // ---- Thread-local holder ----

        @JvmStatic
        fun current(): ToolCallContext = holder.get() ?: EMPTY

        @JvmStatic
        fun set(context: ToolCallContext) {
            holder.set(context)
        }

        @JvmStatic
        fun remove() {
            holder.remove()
        }
    }
}
