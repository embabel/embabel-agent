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
package com.embabel.agent.spi

import com.embabel.agent.core.Blackboard
import com.embabel.common.core.types.HasInfoString

/**
 * Longer-lived interface than a blackboard.
 */
interface Context : HasInfoString {

    /**
     * May be null for a new context not yet saved.
     */
    val id: String?

    fun withId(id: String): Context

    fun bind(
        key: String,
        value: Any,
    )

    fun addObject(value: Any)

    /**
     * Entries in the order they were added.
     * The default instance of any type is the last one
     * Objects are immutable and may not be removed.
     */
    val objects: List<Any>

    fun <T> last(clazz: Class<T>): T? {
        return objects.filterIsInstance(clazz).lastOrNull()
    }

    /**
     * Populate the given blackboard from the context.
     */
    fun populate(blackboard: Blackboard)

}

/**
 * Load a context
 */
interface ContextRepository {

    /**
     * Create an empty context.
     */
    fun create(): Context

    fun save(context: Context): Context

    fun findById(id: String): Context?

    fun delete(context: Context)

}
