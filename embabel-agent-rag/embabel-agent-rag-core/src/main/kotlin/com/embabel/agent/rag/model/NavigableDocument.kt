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

import java.time.Instant

interface NavigableContainerSection : ContainerSection, NavigableSection {

    /**
     * All descendant sections of this container
     */
    fun descendants(): Iterable<NavigableSection> = sequence {
        yieldAll(children)
        children.filterIsInstance<NavigableContainerSection>().forEach { containerChild ->
            yieldAll(containerChild.descendants())
        }
    }.asIterable()

    /**
     * All descendant leaf sections of this container
     */
    fun leaves(): Iterable<LeafSection> = sequence {
        yieldAll(children.filterIsInstance<LeafSection>())
        children.filterIsInstance<NavigableContainerSection>().forEach { containerChild ->
            yieldAll(containerChild.leaves())
        }
    }.asIterable()
}

data class DefaultMaterializedContainerSection(
    override val id: String,
    override val uri: String? = null,
    override val title: String,
    override val children: List<NavigableSection>,
    override val parentId: String? = null,
    override val metadata: Map<String, Any?> = emptyMap(),
) : NavigableContainerSection

/**
 * Document we can navigate descendants of
 */
interface NavigableDocument : ContentRoot, NavigableContainerSection {

    override fun labels(): Set<String> = super<ContentRoot>.labels() + super<NavigableContainerSection>.labels()

    override fun propertiesToPersist(): Map<String, Any?> = super<ContentRoot>.propertiesToPersist()

    /**
     * Return a new document with the given metadata merged with existing metadata.
     * The new metadata takes precedence over existing metadata for duplicate keys.
     */
    fun withMetadata(additionalMetadata: Map<String, Any?>): NavigableDocument

}

/**
 * In-memory representation of a NavigableDocument
 */
data class MaterializedDocument(
    override val id: String,
    override val uri: String,
    override val title: String,
    override val ingestionTimestamp: Instant = Instant.now(),
    override val children: List<NavigableSection>,
    override val metadata: Map<String, Any?> = emptyMap(),
) : NavigableDocument {

    override fun withMetadata(additionalMetadata: Map<String, Any?>): NavigableDocument =
        copy(metadata = metadata + additionalMetadata)
}
