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
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class InMemoryBlackboardSnapshotterTest {

    private val snapshotter = InMemoryBlackboardSnapshotter(
        BlackboardEntrySerializerResolver(
            serializers = emptyList(),
            fallback = JacksonBlackboardEntrySerializer(EmbabelObjectMapperHolder.createDefault().get()),
        )
    )

    @Test
    fun `snapshots and restores entries in order`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val first = SampleValue("first")
        val second = SampleValue("second")
        blackboard.addObject(first)
        blackboard.addObject(second)

        val restored = snapshotter.restore(snapshotter.snapshot(blackboard))

        assertEquals("bb-1", restored.blackboardId)
        assertEquals(listOf(first, second), restored.objects)
    }

    @Test
    fun `snapshots and restores named bindings`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val value = SampleValue("named")
        blackboard["choice"] = value

        val restored = snapshotter.restore(snapshotter.snapshot(blackboard))

        assertEquals(value, restored["choice"])
        assertEquals(listOf(value), restored.objects)
    }

    @Test
    fun `snapshots and restores hidden entries`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val visible = SampleValue("visible")
        val hidden = SampleValue("hidden")
        blackboard.addObject(visible)
        blackboard.addObject(hidden)
        blackboard.hide(hidden)

        val snapshot = snapshotter.snapshot(blackboard)
        val restored = snapshotter.restore(snapshot)

        assertTrue(snapshot.entries.single { it.value.typeName == SampleValue::class.java.name && it.sequence == 1L }.hidden)
        assertEquals(listOf(visible), restored.objects)
    }

    @Test
    fun `snapshots and restores protected bindings`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val value = SampleValue("protected")
        blackboard.bindProtected("user", value)

        val snapshot = snapshotter.snapshot(blackboard)
        val restored = snapshotter.restore(snapshot)

        assertTrue(snapshot.bindings.getValue("user").protected)
        restored.clear()
        assertEquals(value, restored["user"])
        assertEquals(listOf(value), restored.objects)
    }

    @Test
    fun `serializer context includes key and entry sequence`() {
        val recordingSerializer = RecordingSerializer()
        val recordingSnapshotter = InMemoryBlackboardSnapshotter(
            BlackboardEntrySerializerResolver(
                serializers = listOf(recordingSerializer),
                fallback = JacksonBlackboardEntrySerializer(EmbabelObjectMapperHolder.createDefault().get()),
            )
        )
        val blackboard = InMemoryBlackboard("bb-1")
        blackboard.addObject(SampleValue("entry"))
        blackboard.setCondition("condition", true)

        recordingSnapshotter.snapshot(blackboard, processId = "p1")

        assertEquals("p1", recordingSerializer.serializationContexts[0].processId)
        assertEquals(0L, recordingSerializer.serializationContexts[0].entrySequence)
        assertNull(recordingSerializer.serializationContexts[0].key)
        assertEquals("condition", recordingSerializer.serializationContexts[1].key)
    }

    data class SampleValue(
        val value: String,
    )

    private class RecordingSerializer : BlackboardEntrySerializer {

        val serializationContexts = mutableListOf<BlackboardEntrySerializationContext>()

        override fun supportsSerialization(value: Any): Boolean = true

        override fun serialize(
            value: Any,
            context: BlackboardEntrySerializationContext,
        ): SerializedBlackboardValue {
            serializationContexts += context
            return SerializedBlackboardValue(
                typeName = value.javaClass.name,
                contentType = MediaType.TEXT_PLAIN_VALUE,
                payload = value.toString().toByteArray(),
            )
        }

        override fun deserialize(
            value: SerializedBlackboardValue,
            context: BlackboardEntryDeserializationContext,
        ): Any =
            value.payload.toString(Charsets.UTF_8)
    }
}
