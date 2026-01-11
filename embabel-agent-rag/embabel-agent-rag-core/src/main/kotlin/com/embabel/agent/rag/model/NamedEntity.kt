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

import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.util.indent

/**
 * Base contract for any named entity that can be stored and retrieved.
 * This is not intended as a generic type liked NamedEntityData,
 * which exposes properties in a generic manner,
 * but a superinterface for domain classes with strongly typed properties.
 *
 * Domain classes implement this interface to enable:
 * - Storage in [NamedEntityDataRepository]
 * - Hydration from [NamedEntityData]
 * - Vector and text search via [Retrievable]
 *
 * Provides sensible defaults for [Retrievable] methods, so implementations
 * only need to provide [id], [name], and [description].
 *
 * Example:
 * ```kotlin
 * data class Person(
 *     override val id: String,
 *     override val name: String,
 *     override val description: String,
 *     val birthYear: Int,
 * ) : NamedEntity
 * ```
 */
interface NamedEntity : Retrievable, NamedAndDescribed {

    override val id: String
    override val name: String
    override val description: String

    // === Defaults for Datum ===

    override val uri: String?
        get() = null

    override val metadata: Map<String, Any?>
        get() = emptyMap()

    override fun labels(): Set<String> =
        setOf(this::class.simpleName ?: NamedEntityData.ENTITY_LABEL)

    // === Defaults for Embeddable ===

    override fun embeddableValue(): String =
        "$name: $description"

    // === Defaults for HasInfoString ===

    override fun infoString(verbose: Boolean?, indent: Int): String =
        "(${labels().joinToString(":")} id='$id', name=$name)".indent(indent)
}
