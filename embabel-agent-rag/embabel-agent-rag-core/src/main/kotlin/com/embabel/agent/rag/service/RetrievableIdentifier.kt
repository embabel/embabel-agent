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

import com.embabel.agent.rag.model.Retrievable

/**
 * Identifier for any [Retrievable] (Chunk, NamedEntity, etc.).
 * Ensures that ids don't need to be globally unique by namespacing them with a type/label.
 *
 * @param id The unique identifier within its type.
 * @param type The type or namespace (typically a label like "Chunk", "Person", etc.).
 */
data class RetrievableIdentifier(
    val id: String,
    val type: String,
) {

    companion object {

        fun forChunk(id: String) =
            RetrievableIdentifier(id = id, type = "Chunk")

        fun forUser(id: String) =
            RetrievableIdentifier(id = id, type = "User")

        /**
         * Create an identifier from any Retrievable using its first label as the type.
         */
        fun from(retrievable: Retrievable): RetrievableIdentifier =
            RetrievableIdentifier(
                id = retrievable.id,
                type = retrievable.labels().firstOrNull() ?: retrievable::class.simpleName ?: "Unknown"
            )
    }
}

@Deprecated("Use RetrievableIdentifier", ReplaceWith("RetrievableIdentifier"))
typealias EntityIdentifier = RetrievableIdentifier
