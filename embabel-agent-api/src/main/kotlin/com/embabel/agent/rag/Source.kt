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

import java.util.*

/**
 * A Source object instance is an input
 * such as a Chunk or a Fact.
 */
sealed interface Source : Retrievable

/**
 * Implemented by objects that are from a source.
 */
interface Sourced {
    val basis: Retrievable
}

/**
 * Traditional RAG. Text chunk
 */
interface Chunk : Source {

    /**
     * Text content
     */
    val text: String

    override fun embeddableValue(): String = text

    companion object {

        operator fun invoke(
            id: String,
            text: String,
            metadata: Map<String, Any?> = emptyMap(),
        ): Chunk {
            return ChunkImpl(
                id = id,
                text = text,
                metadata = metadata,
            )
        }

    }

    override fun infoString(verbose: Boolean?): String {
        return "chunk: $text"
    }
}

private data class ChunkImpl(
    override val id: String,
    override val text: String,
    override val metadata: Map<String, Any?>,
) : Chunk

/**
 * A fact.
 * @param assertion the text of the fact
 * @param authority the authority of the fact, such as a person
 */
data class Fact(
    val assertion: String,
    val authority: String,
    override val metadata: Map<String, Any?> = emptyMap(),
    override val id: String = UUID.randomUUID().toString(),
) : Source {

    override fun embeddableValue(): String = assertion

    override fun infoString(verbose: Boolean?): String =
        "Fact $id from $authority: $assertion"
}
