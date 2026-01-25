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

import com.embabel.agent.domain.library.HasContent
import java.time.Instant

/**
 * ContentElement that exists in a hierarchy,
 * such as a document with sections and subsections.
 * The hierarchy is represented by parentId references
 * and content does not need to be held in memory.
 */
interface HierarchicalContentElement : ContentElement {

    val parentId: String?

    override fun propertiesToPersist(): Map<String, Any?> = super.propertiesToPersist() + mapOf(
        "parentId" to parentId,
    )
}

/**
 * Root of a structured document
 * It must have a non-null URI
 */
interface ContentRoot : HierarchicalContentElement {

    /**
     * A content root must have a URI
     */
    override val uri: String

    val title: String

    /**
     * A content root has no parent
     */
    override val parentId get() = null

    val ingestionTimestamp: Instant

    override fun propertiesToPersist(): Map<String, Any?> = super.propertiesToPersist() + mapOf(
        "title" to title,
        "ingestionTimestamp" to ingestionTimestamp,
    )

    override fun labels(): Set<String> {
        return super.labels() + setOf("Document", "ContentRoot")
    }
}

sealed interface Section : HierarchicalContentElement {

    val title: String

    override fun propertiesToPersist(): Map<String, Any?> = super.propertiesToPersist() + mapOf(
        "title" to title,
    )

    override fun labels(): Set<String> {
        return super.labels() + setOf("Section")
    }
}

interface NavigableSection : Section {

    /**
     * Direct children of this section (not all descendants).
     */
    val children: Iterable<NavigableSection>
}

interface ContainerSection : Section {

    override fun labels(): Set<String> {
        return super.labels() + setOf("ContainerSection")
    }
}

/**
 * Contains content without further subdivisions.
 */
data class LeafSection(
    override val id: String,
    override val uri: String? = null,
    override val title: String,
    val text: String,
    override val parentId: String? = null,
    override val metadata: Map<String, Any?> = emptyMap(),
) : NavigableSection, Retrievable, HasContent {

    override val content get() = text

    override val children: Iterable<NavigableSection> get() = emptyList()

    override fun propertiesToPersist(): Map<String, Any?> = super<NavigableSection>.propertiesToPersist() + mapOf(
        "text" to content,
    )

    override fun labels(): Set<String> {
        return super<NavigableSection>.labels() + super<Retrievable>.labels() + setOf("LeafSection")
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "id:$id\n${embeddableValue()}"

    override fun embeddableValue(): String {
        return "$title\n$text"
    }
}
