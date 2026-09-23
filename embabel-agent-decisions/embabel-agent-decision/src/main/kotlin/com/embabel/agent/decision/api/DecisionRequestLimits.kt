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
package com.embabel.agent.decision.api

import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

internal object DecisionRequestLimits {
    const val MAX_NESTING_DEPTH = 32
    const val MAX_STATE_NODES = 10_000
    const val MAX_QUESTIONS = 256
    const val MAX_SUPPORT_PER_QUESTION = 256
    const val MAX_TOTAL_SUPPORT = 4_096
    const val MAX_STRING_BYTES = 16_384
    const val MAX_OUTBOUND_BYTES = 1_048_576

    fun snapshotState(values: Map<String, Any?>): Map<String, Any?> = Snapshotter().map(values, 0)

    fun string(value: String, name: String): String {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_STRING_BYTES) {
            "$name must be at most $MAX_STRING_BYTES UTF-8 bytes"
        }
        return value
    }

    fun correlationId(value: String): String {
        require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= 256 && value.codePoints().allMatch(::safeSingleLineCodePoint)) {
            "correlationId must be a nonblank opaque single-line value of at most 256 UTF-8 bytes"
        }
        return value
    }

    fun validateQuestionCounts(questionCount: Int, supportCounts: Iterable<Int>) {
        require(questionCount in 1..MAX_QUESTIONS) { "a decision request needs 1 to $MAX_QUESTIONS questions" }
        var total = 0
        supportCounts.forEach { count ->
            require(count <= MAX_SUPPORT_PER_QUESTION) { "question support exceeds $MAX_SUPPORT_PER_QUESTION entries" }
            total += count
            require(total <= MAX_TOTAL_SUPPORT) { "request support exceeds $MAX_TOTAL_SUPPORT entries" }
        }
    }

    fun validatePreparedBytes(state: Map<String, Any?>, questions: List<PreparedQuestion>) {
        val budget = ByteBudget(MAX_OUTBOUND_BYTES)
        budget.ascii("{\"state\":")
        budget.value(state)
        budget.ascii(",\"questions\":[")
        questions.forEachIndexed { index, question ->
            if (index > 0) budget.ascii(",")
            budget.ascii("{\"id\":")
            budget.string(question.id)
            budget.ascii(",\"kind\":")
            budget.string(question.kind.name)
            budget.ascii(",\"question\":")
            budget.string(question.question)
            budget.ascii(",\"support\":[")
            question.support.forEachIndexed { supportIndex, support ->
                if (supportIndex > 0) budget.ascii(",")
                budget.ascii("{\"id\":")
                budget.string(support.id)
                budget.ascii(",\"label\":")
                budget.string(support.label)
                budget.ascii("}")
            }
            budget.ascii("]}")
        }
        budget.ascii("]}")
    }

    private fun safeSingleLineCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        -> false
        else -> true
    }

    private class Snapshotter {
        private val path = IdentityHashMap<Any, Boolean>()
        private var nodes = 0

        fun map(values: Map<*, *>, depth: Int): Map<String, Any?> = container(values, depth) {
            val result = LinkedHashMap<String, Any?>()
            for ((rawKey, rawValue) in values) {
                require(rawKey is String) { "state map keys must be strings" }
                val key = string(rawKey, "state key")
                result[key] = value(rawValue, depth + 1)
            }
            Collections.unmodifiableMap(result)
        }

        private fun value(value: Any?, depth: Int): Any? {
            require(depth <= MAX_NESTING_DEPTH) { "state nesting exceeds $MAX_NESTING_DEPTH" }
            nodes++
            require(nodes <= MAX_STATE_NODES) { "state nodes exceed $MAX_STATE_NODES" }
            return when (value) {
                null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double,
                is java.math.BigInteger, is java.math.BigDecimal -> value
                is String -> string(value, "state string")
                is Number -> throw IllegalArgumentException("state number values must be immutable primitives")
                is Map<*, *> -> map(value, depth)
                is Iterable<*> -> container(value, depth) {
                    val result = ArrayList<Any?>()
                    for (item in value) result += this.value(item, depth + 1)
                    Collections.unmodifiableList(result)
                }
                is Array<*> -> container(value, depth) {
                    val result = ArrayList<Any?>(value.size)
                    value.forEach { result += this.value(it, depth + 1) }
                    Collections.unmodifiableList(result)
                }
                else -> throw IllegalArgumentException("state values must be scalar, map, or collection")
            }
        }

        private fun <T> container(identity: Any, depth: Int, block: () -> T): T {
            require(depth <= MAX_NESTING_DEPTH) { "state nesting exceeds $MAX_NESTING_DEPTH" }
            require(path.put(identity, true) == null) { "state contains an identity cycle" }
            return try { block() } finally { path.remove(identity) }
        }
    }

    private class ByteBudget(private val maximum: Int) {
        private var count = 0

        fun ascii(value: String) = add(value.length)

        fun string(value: String) {
            add(2)
            value.forEach { character ->
                when (character) {
                    '"', '\\', '\b', '\u000c', '\n', '\r', '\t' -> add(2)
                    else -> if (character < ' ') add(6) else add(character.toString().toByteArray(StandardCharsets.UTF_8).size)
                }
            }
        }

        fun value(value: Any?) {
            when (value) {
                null -> ascii("null")
                is String -> string(value)
                is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double,
                is java.math.BigInteger, is java.math.BigDecimal -> ascii(value.toString())
                is Map<*, *> -> {
                    ascii("{")
                    value.entries.forEachIndexed { index, entry ->
                        if (index > 0) ascii(",")
                        string(entry.key as String)
                        ascii(":")
                        value(entry.value)
                    }
                    ascii("}")
                }
                is Iterable<*> -> {
                    ascii("[")
                    value.forEachIndexed { index, item ->
                        if (index > 0) ascii(",")
                        value(item)
                    }
                    ascii("]")
                }
                else -> throw IllegalArgumentException("unsupported prepared state")
            }
        }

        private fun add(amount: Int) {
            count += amount
            require(count <= maximum) { "decision request exceeds $maximum outbound bytes" }
        }
    }
}
