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
package com.embabel.agent.eval.client

data class ObjectContext(
    val context: String,
    val resources: List<Resource>,
    val functions: List<FunctionMetadata>,
) {
    fun infoString(): String {
        return "ObjectContext(context='$context', resources=${resources.map { it.infoString() }}, functions=${functions.map { it.name }})"
    }

    val entities
        get() : List<Resource> {
            return resources.filter { it.labels.contains("DomainNode") }
        }
}

data class Resource(
    val id: String,
    val name: String,
    val description: String = name,
    val properties: Map<String, Any?> = emptyMap(),
    val labels: Set<String> = emptySet(),
    val aliases: Set<String> = emptySet(),
) {

    fun infoString(): String {
        return labels.joinToString(":") + " {id='$id', name='$name', description='$description'}"
    }
}

data class FunctionMetadata(
    val name: String,
    val description: String,
    val label: String?,
    val view: String? = null,
    val staticMethod: Boolean = false,
//     val nonFunctionalProperties: NonFunctionalProperties = NonFunctionalProperties(),
    val inputTypeSchema: String,
)
