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
     * Every descendant section in the order the document reads: a section before the sections inside
     * it, and those before its next sibling (pre-order). [descendants] is level order instead, which
     * yields a container's children before any grandchild, so it cannot number sections as they read.
     *
     * Each comes with its [SectionPlace.ordinal] (0-based, across the whole tree) and
     * [SectionPlace.depth] (1 for a direct child of this container).
     */
    fun sectionsInReadingOrder(): List<SectionPlace> {
        val places = mutableListOf<SectionPlace>()
        fun visit(sections: List<NavigableSection>, depth: Int) {
            for (section in sections) {
                places += SectionPlace(section, ordinal = places.size, depth = depth)
                if (section is NavigableContainerSection) visit(section.children.toList(), depth + 1)
            }
        }
        visit(children.toList(), depth = 1)
        return places
    }

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

/**
 * Where a [section] falls in the document it belongs to: its [ordinal] in reading order (0-based)
 * and its [depth] below the document (1 for a top-level section).
 */
data class SectionPlace(
    val section: NavigableSection,
    val ordinal: Int,
    val depth: Int,
)

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
