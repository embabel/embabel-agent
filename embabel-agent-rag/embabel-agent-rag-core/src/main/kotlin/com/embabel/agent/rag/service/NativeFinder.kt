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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.model.NamedEntity

/**
 * Strategy for looking up entities via native store mappings (e.g., JPA, Drivine).
 *
 * Implementations bypass generic label-based lookup with their own persistence mapping.
 * Returns null to indicate the type is not supported, allowing fallback to generic lookup.
 */
interface NativeFinder {

    /**
     * Native store lookup by ID for a specific type.
     *
     * @param id the entity ID
     * @param type the target class
     * @return the entity if found via native store, null to fall back to generic lookup
     */
    fun <T : NamedEntity> findById(id: String, type: Class<T>): T? = null

    /**
     * Native store lookup for all entities of a specific type.
     *
     * @param type the target class
     * @return list of entities if type has native mapping, null to fall back to generic lookup
     */
    fun <T : NamedEntity> findAll(type: Class<T>): List<T>? = null

    companion object {

        /**
         * A [NativeFinder] that always returns null, indicating no native support.
         */
        @JvmField
        val NONE: NativeFinder = NoOpNativeFinder()
    }
}

/**
 * A [NativeFinder] that always returns null, indicating no native support.
 */
private class NoOpNativeFinder : NativeFinder
