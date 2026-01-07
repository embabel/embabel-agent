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
package com.embabel.agent.api.common

import com.embabel.common.util.StringTransformer

/**
 * Holds one or more annotated tool objects.
 * Adds a naming strategy and a filter to the overall object.
 * @param objects the objects the tool annotations are on
 * @param namingStrategy the naming strategy to use for the tool object's methods
 * @param filter a filter to apply to the tool object's methods
 */
data class ToolObject(
    val objects: List<Any>,
    val namingStrategy: StringTransformer = StringTransformer.IDENTITY,
    val filter: (String) -> Boolean = { true },
) {

    init {
        if (objects.any { it is Iterable<*> }) {
            throw IllegalArgumentException("Internal error: ToolObject cannot be an Iterable. Offending objects: $objects")
        }
    }

    constructor (
        obj: Any,
        namingStrategy: StringTransformer = StringTransformer.IDENTITY,
        filter: (String) -> Boolean = { true },
    ) : this(listOf(obj), namingStrategy, filter)

    constructor (
        obj: Any,
    ) : this(
        objects = listOf(obj),
        namingStrategy = StringTransformer.IDENTITY,
        filter = { true },
    )

    /**
     * Add a prefix to the tool object's method names.
     */
    fun withPrefix(
        prefix: String,
    ): ToolObject = withNamingStrategy { "$prefix$it" }

    /**
     * Add a naming strategy to the tool object's method names.
     */
    fun withNamingStrategy(
        namingStrategy: StringTransformer,
    ): ToolObject = copy(namingStrategy = namingStrategy)

    fun withFilter(
        filter: (String) -> Boolean,
    ): ToolObject = copy(filter = filter)

    companion object {

        /**
         * Create a ToolObject from any object.
         * If the object is already a ToolObject, return it as is.
         */
        fun from(o: Any): ToolObject = o as? ToolObject
            ?: ToolObject(
                obj = o,
                namingStrategy = StringTransformer.IDENTITY,
                filter = { true },
            )

    }
}
