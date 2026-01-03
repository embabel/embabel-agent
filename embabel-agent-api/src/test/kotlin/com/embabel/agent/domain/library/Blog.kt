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
package com.embabel.agent.domain.library

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.Timestamped
import java.time.Instant

/**
 * Content asset that can be used in different ways: for example
 * in producing different marketing materials.
 */
interface ContentAsset : HasContent, Timestamped, PromptContributor


/**
 * Blog content, specifying its format in a way that will
 * be intelligible to an LLM as well as application code.
 */
data class Blog(
    val title: String,
    val author: String,
    override val content: String,
    override val timestamp: Instant = Instant.now(),
    val keywords: Set<String> = emptySet(),
    val format: String = "markdown",
) : ContentAsset {

    override fun contribution(): String =
        """
            |Blog Post:
            |Title: $title
            |Author: $author
            |Content: $content
            |Date: ${timestamp.atZone(java.time.ZoneId.systemDefault()).toLocalDate()}
        """.trimIndent()
}
