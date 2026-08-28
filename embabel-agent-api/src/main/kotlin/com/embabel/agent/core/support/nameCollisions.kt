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
package com.embabel.agent.core.support

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("com.embabel.agent.core.support.NameCollisions")

/**
 * Collisions already reported, so that a collision on a hot path — tool resolution runs
 * per LLM operation, and the platform's aggregated views are recomputed on every read —
 * is reported once rather than on every pass. Bounded so a pathological caller cannot
 * grow it without limit.
 */
private const val MAX_REPORTED = 1_000
private val reported = ConcurrentHashMap.newKeySet<String>()

/**
 * De-duplicate by name, reporting the elements that are lost.
 *
 * These views are keyed by name and downstream consumers identify their elements by name
 * alone, so a name carried by two different elements cannot be honoured twice and one of
 * them is dropped. Dropping is not itself the problem — two declarations of the very same
 * element collapse harmlessly. Dropping *silently* is, because the capability simply stops
 * existing with nothing in the log to say so.
 *
 * @param kind what is being de-duplicated, for the log message
 * @param sameValue whether two elements sharing a name are in fact the same thing, and so
 * safe to collapse. Defaults to equality, which is meaningful for the value types the
 * platform aggregates; callers holding types without value semantics should pass identity.
 * @param context optional owner or resolution context to include in the report
 * @param describe how to identify retained and dropped elements in the report
 * @param name the name to de-duplicate on
 */
internal fun <T : Any> Iterable<T>.distinctByNameReportingCollisions(
    kind: String,
    sameValue: (T, T) -> Boolean = { a, b -> a == b },
    context: String? = null,
    describe: (T) -> String = { it.toString() },
    name: (T) -> String,
): List<T> {
    val candidates = toList()
    val kept = LinkedHashMap<String, T>()
    val collisions = mutableListOf<Triple<String, T, T>>()
    for (element in candidates) {
        val elementName = name(element)
        val incumbent = kept.putIfAbsent(elementName, element)
        if (incumbent != null && !sameValue(incumbent, element)) {
            collisions += Triple(elementName, incumbent, element)
        }
    }
    collisions.forEach { (elementName, incumbent, dropped) ->
        report(
            kind = kind,
            name = elementName,
            context = context,
            candidateCount = candidates.size,
            publishedCount = kept.size,
            retained = describe(incumbent),
            dropped = describe(dropped),
        )
    }
    return kept.values.toList()
}

private fun report(
    kind: String,
    name: String,
    context: String?,
    candidateCount: Int,
    publishedCount: Int,
    retained: String,
    dropped: String,
) {
    val reportKey = "$kind/${context ?: "unspecified"}/$name"
    if (reported.size < MAX_REPORTED && reported.add(reportKey)) {
        logger.error(
            "🛑 Two different {}s are named '{}': reduced {} candidate entries to {} published entries " +
                "in context '{}'. Retained: {}; dropped: {}. " +
                "Names must be unique because downstream consumers identify {}s by name alone.",
            kind,
            name,
            candidateCount,
            publishedCount,
            context ?: "unspecified",
            retained,
            dropped,
            kind,
        )
    }
}
