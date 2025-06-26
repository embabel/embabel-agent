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
package com.embabel.common.ai.model

import com.embabel.common.core.types.HasInfoString
import org.springframework.ai.model.Model

enum class ModelType {
    LLM, EMBEDDING,
}

/**
 * Metadata about an AI model.
 * Pure data.
 */
interface ModelMetadata {

    /**
     * Name of the LLM, such as "gpt-3.5-turbo"
     */
    val name: String

    /**
     * Name of the provider, such as "OpenAI"
     */
    val provider: String

    val type: ModelType
}

/**
 * Wraps a Spring AI model and allows metadata to be attached to a model
 */
interface AiModel<M : Model<*, *>> : ModelMetadata, HasInfoString {

    val model: M

    override fun infoString(verbose: Boolean?): String =
        "name: $name, provider: $provider"
}
