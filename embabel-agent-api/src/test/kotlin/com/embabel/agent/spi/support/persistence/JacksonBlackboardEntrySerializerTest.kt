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

import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class JacksonBlackboardEntrySerializerTest {

    private val serializer = JacksonBlackboardEntrySerializer(
        EmbabelObjectMapperHolder.createDefault().get()
    )

    @Test
    fun `serializes and deserializes data class`() {
        val value = SampleValue("Castle", 42)

        val serialized = serializer.serialize(value)
        val restored = serializer.deserialize(serialized)

        assertEquals(MediaType.APPLICATION_JSON_VALUE, serialized.contentType)
        assertEquals(SampleValue::class.java.name, serialized.typeName)
        assertEquals(value, restored)
    }

    @Test
    fun `rejects non json content type`() {
        val serialized = SerializedBlackboardValue(
            typeName = SampleValue::class.java.name,
            contentType = MediaType.APPLICATION_XML_VALUE,
            payload = "<value />".toByteArray(),
        )

        assertThrows(AgentProcessPersistenceException::class.java) {
            serializer.deserialize(serialized)
        }
    }

    @Test
    fun `unknown class name throws AgentProcessPersistenceException`() {
        val serialized = SerializedBlackboardValue(
            typeName = "com.example.NonExistentClass",
            contentType = MediaType.APPLICATION_JSON_VALUE,
            payload = "{}".toByteArray(),
        )

        assertThrows(AgentProcessPersistenceException::class.java) {
            serializer.deserialize(serialized)
        }
    }

    @Test
    fun `corrupt payload throws AgentProcessPersistenceException`() {
        val serialized = SerializedBlackboardValue(
            typeName = SampleValue::class.java.name,
            contentType = MediaType.APPLICATION_JSON_VALUE,
            payload = "not valid json".toByteArray(),
        )

        assertThrows(AgentProcessPersistenceException::class.java) {
            serializer.deserialize(serialized)
        }
    }

    data class SampleValue(
        val choice: String,
        val score: Int,
    )
}
