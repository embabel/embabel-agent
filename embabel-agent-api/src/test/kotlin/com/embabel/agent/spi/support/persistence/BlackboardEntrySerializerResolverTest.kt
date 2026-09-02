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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType

class BlackboardEntrySerializerResolverTest {

    @Test
    fun `uses custom serializer before fallback for serialization`() {
        val resolver = BlackboardEntrySerializerResolver(
            serializers = listOf(EntityReferenceSerializer),
            fallback = FallbackSerializer,
        )

        val serialized = resolver.serialize(CustomerEntity("42"))

        assertEquals("entity-reference", serialized.typeName)
    }

    @Test
    fun `uses custom serializer before fallback for deserialization`() {
        val resolver = BlackboardEntrySerializerResolver(
            serializers = listOf(EntityReferenceSerializer),
            fallback = FallbackSerializer,
        )
        val serialized = SerializedBlackboardValue(
            typeName = "entity-reference",
            contentType = MediaType.APPLICATION_JSON_VALUE,
            payload = """{"id":"42"}""".toByteArray(),
        )

        val restored = resolver.deserialize(serialized)

        assertEquals(CustomerEntity("42"), restored)
    }

    @Test
    fun `uses fallback when no custom serializer supports value`() {
        val resolver = BlackboardEntrySerializerResolver(
            serializers = listOf(EntityReferenceSerializer),
            fallback = FallbackSerializer,
        )

        val serialized = resolver.serialize("plain")

        assertEquals("fallback", serialized.typeName)
    }

    @Test
    fun `honors spring ordering`() {
        val resolver = BlackboardEntrySerializerResolver(
            serializers = listOf(LaterStringSerializer, EarlierStringSerializer),
            fallback = FallbackSerializer,
        )

        val serialized = resolver.serialize("ordered")

        assertEquals("earlier-string", serialized.typeName)
    }

    data class CustomerEntity(
        val id: String,
    )

    object EntityReferenceSerializer : BlackboardEntrySerializer {

        override fun supportsSerialization(value: Any): Boolean =
            value is CustomerEntity

        override fun supportsDeserialization(value: SerializedBlackboardValue): Boolean =
            value.typeName == "entity-reference"

        override fun serialize(
            value: Any,
            context: BlackboardEntrySerializationContext,
        ): SerializedBlackboardValue =
            SerializedBlackboardValue(
                typeName = "entity-reference",
                contentType = MediaType.APPLICATION_JSON_VALUE,
                payload = """{"id":"${(value as CustomerEntity).id}"}""".toByteArray(),
            )

        override fun deserialize(
            value: SerializedBlackboardValue,
            context: BlackboardEntryDeserializationContext,
        ): Any =
            CustomerEntity("42")
    }

    object FallbackSerializer : BlackboardEntrySerializer {

        override fun supportsSerialization(value: Any): Boolean = true

        override fun serialize(
            value: Any,
            context: BlackboardEntrySerializationContext,
        ): SerializedBlackboardValue =
            SerializedBlackboardValue(
                typeName = "fallback",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                payload = value.toString().toByteArray(),
            )

        override fun deserialize(
            value: SerializedBlackboardValue,
            context: BlackboardEntryDeserializationContext,
        ): Any =
            value.payload.toString(Charsets.UTF_8)
    }

    @Order(1)
    object EarlierStringSerializer : BlackboardEntrySerializer {

        override fun supportsSerialization(value: Any): Boolean =
            value is String

        override fun serialize(
            value: Any,
            context: BlackboardEntrySerializationContext,
        ): SerializedBlackboardValue =
            SerializedBlackboardValue(
                typeName = "earlier-string",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                payload = value.toString().toByteArray(),
            )

        override fun deserialize(
            value: SerializedBlackboardValue,
            context: BlackboardEntryDeserializationContext,
        ): Any =
            value.payload.toString(Charsets.UTF_8)
    }

    @Order(2)
    object LaterStringSerializer : BlackboardEntrySerializer {

        override fun supportsSerialization(value: Any): Boolean =
            value is String

        override fun serialize(
            value: Any,
            context: BlackboardEntrySerializationContext,
        ): SerializedBlackboardValue =
            SerializedBlackboardValue(
                typeName = "later-string",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                payload = value.toString().toByteArray(),
            )

        override fun deserialize(
            value: SerializedBlackboardValue,
            context: BlackboardEntryDeserializationContext,
        ): Any =
            value.payload.toString(Charsets.UTF_8)
    }
}
