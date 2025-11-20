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
package com.embabel.agent.rag.model

import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.util.indent

/**
 * Adds a name to the well known entity data.
 */
interface NamedEntityData : EntityData, NamedAndDescribed {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val labelsString = labels().joinToString(":")
        return "(${labelsString} id='$id', name=$name, description=$description)".indent(indent)
    }
}

data class SimpleNamedEntityData(
    override val id: String,
    override val uri: String? = null,
    override val name: String,
    override val description: String,
    val labels: Set<String>,
    override val properties: Map<String, Any>,
    override val metadata: Map<String, Any?> = emptyMap(),
) : NamedEntityData {

    override fun labels() = labels + super.labels()

    override fun embeddableValue(): String {
        var sup = super.embeddableValue()
        if (!sup.contains("name")) {
            sup += ", name=$name"
        }
        if (!sup.contains("description")) {
            sup += ", description=$description"
        }
        return sup
    }

}
