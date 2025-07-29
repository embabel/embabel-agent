/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.core.Blackboard
import com.embabel.common.util.indent
import com.embabel.common.util.indentLines
import java.util.*

/**
 * Simple in memory blackboard implementation
 * backed by a map.
 */
class InMemoryBlackboard(
    override val blackboardId: String = UUID.randomUUID().toString(),
) : Blackboard {
    private val _map: MutableMap<String, Any> = mutableMapOf()
    private val _entries: MutableList<Any> = mutableListOf()

    override fun spawn(): Blackboard {
        return InMemoryBlackboard().apply {
            _map.putAll(this@InMemoryBlackboard._map)
            _entries.addAll(this@InMemoryBlackboard._entries)
        }
    }

    override val objects: List<Any> get() = _entries

    override fun get(name: String): Any? = _map[name]

    override fun bind(
        key: String,
        value: Any,
    ): Blackboard {
        _map[key] = value
        _entries.add(value)
        return this
    }

    override operator fun plusAssign(value: Any) {
        addObject(value)
    }

    override fun plusAssign(pair: Pair<String, Any>) {
        bind(pair.first, pair.second)
    }

    override operator fun set(
        key: String,
        value: Any,
    ) {
        bind(key, value)
    }

    override fun setCondition(
        key: String,
        value: Boolean,
    ): Blackboard {
        _map[key] = value
        _entries.add(value)
        return this
    }

    override fun getCondition(key: String): Boolean? =
        _map[key] as? Boolean

    override fun addObject(value: Any): Blackboard {
        _entries.add(value)
        return this
    }

    override fun expressionEvaluationModel(): Map<String, Any> {
        return _map
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val joiner = if (verbose == true) "\n" else ", "
        val mapString =
            if (verbose == true) "\n" + _map.entries.joinToString(joiner) else _map.entries.joinToString(joiner) { "${it.key}=${it.value::class.simpleName}" }
        val entriesString =
            if (verbose == true) "\n" + objects.joinToString(joiner) else objects.map { "${it::class.simpleName}" }
        return """
            |${javaClass.simpleName}: id=$blackboardId
            |map:
            |${_map.entries.joinToString(", ").indent(1)}
            |entries:
            |${objects.joinToString(", ").indent(1)}
            |"""
            .trimMargin()
            .indentLines(indent)
    }
}
