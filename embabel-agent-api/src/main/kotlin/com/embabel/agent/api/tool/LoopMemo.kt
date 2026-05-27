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

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Memoisation helper for "is this the first time we've done X in the current
 * agentic loop?" — the canonical pattern for one-shot tool work that should
 * be paid once per LLM tool-loop iteration regardless of how many times the
 * LLM invokes the host tool.
 *
 * Use cases:
 *
 * - A skill-activation tool returns its body on the first call but a stern
 *   "already activated this turn" message on repeat calls (avoiding
 *   gateway-confusion loops where the LLM keeps trying different arg shapes).
 * - A `describe(namespace)` tool prepends bulky code-writing guidance on the
 *   first describe per loop only — subsequent describes carry just the
 *   namespace body since the LLM already has the guidance in conversation
 *   history.
 * - Any other "if I've already kicked this off this turn, don't redo it"
 *   gating where the dedup is loop-scoped (NOT process-scoped — different
 *   loops in the same agent process get distinct ids).
 *
 * Backed by a thread-safe bounded LRU set keyed on the loop id read from
 * [ToolCallContext.loopId]. When [maxTracked] entries is exceeded the
 * oldest is evicted — exact upper bound, no transient overshoot under
 * concurrent writers.
 *
 * Fallback when the context has no loop id (callers that didn't set one —
 * bare tests, out-of-loop invocations): [firstTimeIn] always returns
 * `true`, on the safe-but-occasionally-noisy assumption that emitting the
 * one-shot work is the less-bad failure mode vs swallowing it forever.
 *
 * Example:
 * ```kotlin
 * private val memo = LoopMemo()
 *
 * override fun call(input: String, context: ToolCallContext): Tool.Result =
 *     if (memo.firstTimeIn(context)) Tool.Result.text(skillBody)
 *     else Tool.Result.text("Already activated this turn — see prior result.")
 * ```
 */
class LoopMemo @JvmOverloads constructor(
    /**
     * Cap on the number of distinct loop ids tracked. When exceeded, the
     * oldest id is evicted (true LRU via [LinkedHashMap.removeEldestEntry]).
     * Default 1024 — comfortable for thousands of conversations against an
     * assistant restart cadence of days, with ~130 KB worst-case memory.
     */
    private val maxTracked: Int = DEFAULT_MAX_TRACKED,
) {

    private val seen: MutableSet<String> = boundedLruSet(maxTracked)

    /**
     * Returns `true` the first time [context]'s [ToolCallContext.loopId] is
     * seen by this memo, `false` on subsequent calls within the same loop.
     * When the context has no loop id, returns `true` every call.
     *
     * Thread-safe — concurrent calls with the same loop id agree on which
     * one is "first" (gets `true`) and which are repeats (get `false`).
     */
    fun firstTimeIn(context: ToolCallContext): Boolean {
        val id = context.loopId() ?: return true
        return seen.add(id)
    }

    companion object {
        const val DEFAULT_MAX_TRACKED: Int = 1024
    }
}

/**
 * Thread-safe bounded LRU [MutableSet] backed by a [LinkedHashMap] with
 * [LinkedHashMap.removeEldestEntry] eviction. Used by [LoopMemo] for
 * loop-id tracking; could be lifted to public if other tools want the same
 * pattern, but for now it's the implementation detail of one helper.
 *
 * Synchronized at the set level — single-op atomicity is enough since we
 * never iterate, only `add`.
 */
private fun <T> boundedLruSet(maxSize: Int): MutableSet<T> =
    Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<T, Boolean>(64, 0.75f, false) {
                override fun removeEldestEntry(eldest: Map.Entry<T, Boolean>?): Boolean = size > maxSize
            }
        )
    )
