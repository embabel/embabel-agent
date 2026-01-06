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

/**
 * Identifier for an entity lookup.
 * Ensures that ids don't need to be globally unique by namespacing them with a type.
 *
 * @param id The unique identifier of the entity within its type.
 * @param type The type or namespace of the entity. This may be only one of multiple labels.
 */
data class EntityIdentifier(
    val id: String,
    val type: String,
) {

    companion object {

        fun forUser(id: String) =
            EntityIdentifier(id = id, type = "User")
    }
}
