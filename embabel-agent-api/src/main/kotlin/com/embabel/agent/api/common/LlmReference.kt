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
package com.embabel.agent.api.common

import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.NamedAndDescribed

/**
 * An LLmReference exposes tools and is a prompt contributor.
 * The prompt contribution might describe how to use the tools
 * or can include relevant information directly.
 * Consider, for example, a reference to an API which is so small it's
 * included in the prompt, versus a large API which must be
 * accessed via tools.
 * The reference name is used in a strategy for tool naming, so should be fairly short.
 * Description may be more verbose.
 * If you want a custom naming strategy, use a ToolObject directly,
 * and add the PromptContributor separately.
 */
interface LlmReference : NamedAndDescribed, PromptContributor {

    /**
     * A safe prefix for LLM tools associated with this reference.
     * Defaults to the name lowercased with spaces replaced by underscores.
     * Subclasses can override it
     */
    fun toolPrefix(): String = name.replace(Regex("[^a-zA-Z0-9 ]"), "_").lowercase()

    /**
     * Create a tool object for this reference.
     */
    fun toolObject(): ToolObject = ToolObject(
        obj = toolInstance(),
        namingStrategy = { toolName -> "${toolPrefix()}_$toolName" },
    )

    /**
     * Return the instance of the tool object. Defaults to this
     */
    fun toolInstance(): Any = this

    override fun contribution(): String {
        return """|
            |Reference: $name
            |Description: $description
            |Tool prefix: ${toolPrefix()}
            |Notes: ${notes()}
        """.trimMargin()
    }

    /**
     * Notes about this reference, such as usage guidance.
     * Does not need to consider prompt prefix, name or description as
     * they will be added automatically.
     */
    fun notes(): String
}
