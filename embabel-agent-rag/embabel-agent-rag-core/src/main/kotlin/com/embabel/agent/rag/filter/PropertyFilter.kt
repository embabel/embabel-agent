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
@file:Suppress("DEPRECATION")

package com.embabel.agent.rag.filter

import com.embabel.agent.rag.model.Datum
import com.embabel.agent.rag.model.NamedEntityData

/**
 * Backward-compatible typealias. Use [com.embabel.agent.filter.PropertyFilter] directly.
 */
@Deprecated(
    "Moved to com.embabel.agent.filter.PropertyFilter",
    ReplaceWith("PropertyFilter", "com.embabel.agent.filter.PropertyFilter"),
)
typealias PropertyFilter = com.embabel.agent.filter.PropertyFilter

/**
 * Extension function to check if a Datum matches a metadata filter.
 */
fun Datum.matchesMetadataFilter(filter: com.embabel.agent.filter.PropertyFilter?): Boolean {
    if (filter == null) return true
    return InMemoryPropertyFilter.matchesMetadata(filter, metadata)
}

/**
 * Extension function to check if a NamedEntityData matches a property filter.
 */
fun NamedEntityData.matchesPropertyFilter(filter: com.embabel.agent.filter.PropertyFilter?): Boolean {
    if (filter == null) return true
    return InMemoryPropertyFilter.matchesProperties(filter, properties)
}
