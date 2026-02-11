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
package com.embabel.common.ai.converters

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.core.ParameterizedTypeReference
import java.lang.reflect.Type

/**
 * Extension of [JacksonOutputConverter] that allows for filtering of properties of the generated object via a predicate.
 */
open class FilteringJacksonOutputConverter<T> private constructor(
    type: Type,
    objectMapper: ObjectMapper,
    private val propertyFilter: JacksonPropertyFilter,
) : JacksonOutputConverter<T>(type, objectMapper) {

    constructor(
        clazz: Class<T>,
        objectMapper: ObjectMapper,
        propertyFilter: JacksonPropertyFilter,
    ) : this(clazz as Type, objectMapper, propertyFilter)

    constructor(
        typeReference: ParameterizedTypeReference<T>,
        objectMapper: ObjectMapper,
        propertyFilter: JacksonPropertyFilter,
    ) : this(typeReference.type, objectMapper, propertyFilter)

    override fun postProcessSchema(jsonNode: JsonNode) {
        val ct = this.objectMapper.typeFactory.constructType(this.type)
        val root = jsonNode as? ObjectNode ?: return

        if (!requiresRecursivePruning(propertyFilter)) {
            pruneTopLevelProperties(root)
            return
        }

        val schemaPruner = SchemaPruner(
            objectMapper = objectMapper,
            shouldKeepProperty = { _, property ->
                val field = property.field
                field == null || propertyFilter.test(field)
            },
            shouldKeepTopLevelPropertyName = { propertyName ->
                propertyFilter.test(propertyName)
            }
        )
        schemaPruner.pruneAndSweep(ct, root)
    }

    private fun requiresRecursivePruning(filter: JacksonPropertyFilter): Boolean =
        when (filter) {
            is JacksonPropertyFilter.MatchesPropertyValue -> false
            is JacksonPropertyFilter.SkipAnnotation -> true
            is JacksonPropertyFilter.Composite -> filter.filters.any { requiresRecursivePruning(it) }
        }

    private fun pruneTopLevelProperties(schemaRoot: ObjectNode) {
        val propsNode = schemaRoot.get("properties") as? ObjectNode ?: return
        val iterator = propsNode.fieldNames() as MutableIterator<String>
        while (iterator.hasNext()) {
            val name = iterator.next()
            if (propertyFilter.test(name)) continue
            iterator.remove()
            removeRequiredProperty(schemaRoot, name)
        }
    }

    private fun removeRequiredProperty(objSchema: ObjectNode, propertyName: String) {
        val required = objSchema.get("required") as? ArrayNode ?: return
        for (i in required.size() - 1 downTo 0) {
            val requiredName = required.get(i)
            if (requiredName.isTextual && requiredName.asText() == propertyName) {
                required.remove(i)
            }
        }
        if (required.size() == 0) {
            objSchema.remove("required")
        }
    }

}
