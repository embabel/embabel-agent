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
package com.embabel.agent.spi.support.persistence

import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import tools.jackson.databind.ObjectMapper
import org.springframework.http.MediaType

/**
 * Default JSON serializer for blackboard entries.
 *
 * Uses the caller-provided Embabel [ObjectMapper]. The serializer is stateless
 * and suitable for shared use. It records the runtime JVM class name as the
 * value type and restores through that class, which is appropriate for simple
 * DTOs, Kotlin data classes, records, primitives, and collections that Jackson
 * can handle.
 *
 * Applications needing different semantics, such as storing JPA entities as
 * references instead of materialized object graphs, should provide a more
 * specific [BlackboardEntrySerializer] ahead of this fallback.
 */
class JacksonBlackboardEntrySerializer(
    private val objectMapper: ObjectMapper,
) : BlackboardEntrySerializer {

    override fun supportsSerialization(value: Any): Boolean = true

    override fun serialize(
        value: Any,
        context: BlackboardEntrySerializationContext,
    ): SerializedBlackboardValue =
        SerializedBlackboardValue(
            typeName = value.javaClass.name,
            contentType = MediaType.APPLICATION_JSON_VALUE,
            payload = objectMapper.writeValueAsBytes(value),
        )

    override fun deserialize(
        value: SerializedBlackboardValue,
        context: BlackboardEntryDeserializationContext,
    ): Any {
        require(MediaType.parseMediaType(value.contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            "Unsupported blackboard value content type [${value.contentType}]"
        }
        return objectMapper.readValue(value.payload, Class.forName(value.typeName))
    }
}
