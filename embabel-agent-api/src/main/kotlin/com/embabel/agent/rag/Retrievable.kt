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
package com.embabel.agent.rag

import com.embabel.agent.api.common.Embedding
import com.embabel.common.core.types.HasInfoString
import org.jetbrains.annotations.ApiStatus

/**
 * Embedded object instance.
 */
interface Embedded {

    val embedding: Embedding?

}


interface ContentElement {

    val id: String

    val metadata: Map<String, Any?>
}

/**
 * A Retrievable object instance is a chunk or an entity
 * It has a stable id.
 */
interface Retrievable : HasInfoString, ContentElement {

    /**
     * Embedding value of this retrievable object.
     */
    fun embeddableValue(): String

    /**
     * Neighbors of this retrievable object.
     * Allows navigation of a graph
     */
    @get:ApiStatus.Experimental
    val neighbors: Map<String, Collection<Retrievable>> get() = mapOf()

}
