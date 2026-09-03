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
import org.springframework.core.annotation.AnnotationAwareOrderComparator

/**
 * Selects the serializer for blackboard values.
 *
 * Spring supplies custom serializers as an ordered list of beans. This resolver
 * applies that ordering, tries custom serializers first, and delegates to the
 * fallback serializer when no custom serializer supports the value.
 *
 * Serialization and deserialization use different dispatch paths: serialization
 * matches on the live object via [BlackboardEntrySerializer.supportsSerialization],
 * deserialization matches on the stored payload via
 * [BlackboardEntrySerializer.supportsDeserialization]. A serializer must write a
 * unique [com.embabel.agent.core.persistence.SerializedBlackboardValue.typeName]
 * and match on it in [BlackboardEntrySerializer.supportsDeserialization]; otherwise
 * the fallback serializer handles restore, which will likely fail or produce
 * wrong results. Mismatches only surface at restore time, not at write time.
 */
internal class BlackboardEntrySerializerResolver(
    serializers: List<BlackboardEntrySerializer>,
    private val fallback: BlackboardEntrySerializer,
) {

    private val serializers: List<BlackboardEntrySerializer> =
        serializers.toMutableList().apply {
            AnnotationAwareOrderComparator.sort(this)
        }

    fun serialize(
        value: Any,
        context: BlackboardEntrySerializationContext = BlackboardEntrySerializationContext(),
    ): SerializedBlackboardValue =
        serializerFor(value).serialize(value, context)

    fun deserialize(
        value: SerializedBlackboardValue,
        context: BlackboardEntryDeserializationContext = BlackboardEntryDeserializationContext(),
    ): Any =
        serializerFor(value).deserialize(value, context)

    private fun serializerFor(value: Any): BlackboardEntrySerializer =
        serializers.firstOrNull { it.supportsSerialization(value) } ?: fallback

    private fun serializerFor(value: SerializedBlackboardValue): BlackboardEntrySerializer =
        serializers.firstOrNull { it.supportsDeserialization(value) } ?: fallback
}
