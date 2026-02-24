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
package com.embabel.agent.rag.service.support

import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NativeFinder

/**
 * A [com.embabel.agent.rag.service.NativeFinder] that tries multiple finders in order, returning the first non-null result.
 *
 * Useful for composing multiple native store mappings (e.g., JPA for some types, Drivine for others).
 *
 * @param finders the ordered list of finders to try
 */
class ChainedNativeFinder(
    private val finders: List<NativeFinder>,
) : NativeFinder {

    constructor(vararg finders: NativeFinder) : this(finders.toList())

    override fun <T : NamedEntity> findById(id: String, type: Class<T>): T? {
        for (finder in finders) {
            finder.findById(id, type)?.let { return it }
        }
        return null
    }

    override fun <T : NamedEntity> findAll(type: Class<T>): List<T>? {
        for (finder in finders) {
            finder.findAll(type)?.let { return it }
        }
        return null
    }
}
