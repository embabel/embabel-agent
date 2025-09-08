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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.ContainerSection
import com.embabel.agent.rag.ContentRoot
import com.embabel.agent.rag.LeafSection
import com.embabel.agent.rag.MaterializedSection

interface MaterializedContainerSection : ContainerSection, MaterializedSection {

    /**
     * Direct children of this section (not all descendants).
     */
    val children: List<MaterializedSection>

    fun descendants(): List<MaterializedSection> =
        children + children.filterIsInstance<MaterializedContainerSection>().flatMap { containerChild ->
            containerChild.descendants()
        }

    fun leaves(): List<LeafSection> =
        children.filterIsInstance<LeafSection>() +
                children.filterIsInstance<MaterializedContainerSection>().flatMap { containerChild ->
                    containerChild.leaves()
                }
}

data class DefaultMaterializedContainerSection(
    override val id: String,
    override val uri: String? = null,
    override val title: String,
    override val children: List<MaterializedSection>,
    override val parentId: String? = null,
    override val metadata: Map<String, Any?> = emptyMap(),
) : MaterializedContainerSection

data class MaterializedDocument(
    override val id: String,
    override val uri: String? = null,
    override val title: String,
    override val children: List<MaterializedSection>,
    override val metadata: Map<String, Any?> = emptyMap(),
) : MaterializedContainerSection, ContentRoot {

    override fun labels(): Set<String> = super<ContentRoot>.labels() + super<MaterializedContainerSection>.labels()

}
