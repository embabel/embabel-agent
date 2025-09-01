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
package com.embabel.agent.spi.support

import com.embabel.agent.core.Blackboard
import com.embabel.agent.spi.Context
import com.embabel.common.util.indent
import com.embabel.common.util.indentLines
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class SimpleContext(
    override var id: String? = null,
) : Context {

    private val _map: MutableMap<String, Any> = ConcurrentHashMap()
    private val _entries: MutableList<Any> = Collections.synchronizedList(mutableListOf())

    override fun withId(id: String): Context {
        this.id = id
        return this
    }

    override fun bind(
        key: String,
        value: Any,
    ) {
        _map[key] = value
        _entries.add(value)
    }

    override fun addObject(value: Any) {
        _entries.add(value)
    }

    override val objects: List<Any>
        get() = synchronized(_entries) {
            _entries.toList() // Return a snapshot to avoid concurrent modification
        }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        // Create snapshots for thread-safe iteration
        val mapSnapshot = _map.toMap()
        val objectsSnapshot = synchronized(_entries) { _entries.toList() }

        return """
            |${javaClass.simpleName}: id=$id
            |map:
            |${mapSnapshot.entries.joinToString(", ").indent(1)}
            |entries:
            |${objectsSnapshot.joinToString(", ").indent(1)}
            |"""
            .trimMargin()
            .indentLines(indent)
    }

    override fun populate(blackboard: Blackboard) {
        _map.forEach { (k, v) -> blackboard[k] = v }
        _entries.filterNot { _map.values.contains(it) }.forEach { blackboard.addObject(it) }
    }
}
