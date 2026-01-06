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
package com.embabel.agent.api.common.reference

import com.embabel.agent.api.common.LlmReference
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Contents of a Spring resource as an LlmReference.
 * Read and held in memory.
 */
data class SpringResource @JsonCreator constructor(
    @field:JsonProperty("resourcePath") val resourcePath: String,
    override val name: String = resourcePath,
    override val description: String = "Spring resource at $resourcePath",
) : LlmReferenceProvider, LlmReference {

    override fun reference(): LlmReference = this

    override fun notes(): String = try {
        val resource: Resource = DefaultResourceLoader().getResource(resourcePath)
        val content = resource.getContentAsString(StandardCharsets.UTF_8)
        "Resource content:\n$content"
    } catch (e: IOException) {
        "Failed to read resource: ${e.message}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpringResource) return false
        return resourcePath == other.resourcePath && description == other.description
    }

    override fun hashCode(): Int {
        var result = resourcePath.hashCode()
        result = 31 * result + description.hashCode()
        return result
    }

    override fun toString(): String =
        "SpringResource(resourcePath='$resourcePath', description='$description')"
}
