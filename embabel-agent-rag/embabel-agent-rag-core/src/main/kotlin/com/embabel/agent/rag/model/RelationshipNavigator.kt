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
package com.embabel.agent.rag.model

import com.embabel.agent.rag.service.RetrievableIdentifier

/**
 * Provides relationship navigation capability for [NamedEntityInvocationHandler].
 *
 * This interface is implemented by repositories that support relationship traversal.
 * It allows the invocation handler to lazily load related entities when a method
 * annotated with `@Relationship` is invoked.
 */
interface RelationshipNavigator {
    /**
     * Find entities related to the given entity via a named relationship.
     *
     * @param source identifier for the source entity (id + type)
     * @param relationshipName the relationship type/name
     * @param direction the direction of traversal
     * @return list of related entity data
     */
    fun findRelated(
        source: RetrievableIdentifier,
        relationshipName: String,
        direction: RelationshipDirection,
    ): List<NamedEntityData>
}
