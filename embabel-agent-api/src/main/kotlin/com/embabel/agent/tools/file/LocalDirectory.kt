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
package com.embabel.agent.tools.file

import com.embabel.agent.api.reference.LlmReference
import com.embabel.common.util.StringTransformer

/**
 * Readonly access to a project on the local filesystem.
 */
data class LocalDirectory @JvmOverloads constructor(
    override val root: String,
    override val description: String,
    val notes: String = "",
    override val fileContentTransformers: List<StringTransformer> =
        listOf(WellKnownFileContentTransformers.removeApacheLicenseHeader),
) : FileReadTools, PatternSearch,
    FileReadLog by DefaultFileReadLog(),
    LlmReference {

    override val name: String get() = root.substringAfterLast('/')

    override fun notes() = notes

    /**
     * Returns a copy of this LocalDirectory with the given usage notes.
     */
    fun withUsageNotes(notes: String) = copy(notes = notes)
}
