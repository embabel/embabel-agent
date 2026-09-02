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

import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.system.measureNanoTime

class JacksonAgentProcessStateSerializerTest {

    private val createdAt = Instant.parse("2026-08-29T00:00:00Z")
    private val updatedAt = Instant.parse("2026-08-29T00:01:00Z")
    private val serializer = JacksonAgentProcessStateSerializer(
        objectMapper = EmbabelObjectMapperHolder.createDefault().get(),
        clock = Clock.fixed(updatedAt, ZoneOffset.UTC),
    )

    @Test
    fun `serializes snapshot envelope fields`() {
        val snapshot = snapshot()

        val serialized = serializer.serialize(snapshot)

        assertEquals("p1", serialized.processId)
        assertEquals("parent", serialized.parentId)
        assertEquals("test-agent", serialized.agentName)
        assertEquals(AgentProcessStatusCode.WAITING, serialized.status)
        assertEquals(3, serialized.version)
        assertEquals(MediaType.APPLICATION_JSON, serialized.contentType)
        assertEquals(createdAt, serialized.createdAt)
        assertEquals(updatedAt, serialized.updatedAt)
        assertEquals(mapOf("schema" to "test"), serialized.metadata)
    }

    @Test
    fun `deserializes snapshot payload`() {
        val snapshot = snapshot()
        val serialized = serializer.serialize(snapshot)

        val restored = serializer.deserialize(serialized)

        assertEquals(snapshot, restored)
    }

    @Test
    fun `rejects non json content type`() {
        val serialized = SerializedAgentProcessSnapshot(
            processId = "p1",
            parentId = null,
            agentName = "test-agent",
            status = AgentProcessStatusCode.WAITING,
            version = 1,
            contentType = MediaType.APPLICATION_XML,
            payload = "<snapshot />".toByteArray(),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        assertThrows(AgentProcessPersistenceException::class.java) {
            serializer.deserialize(serialized)
        }
    }

    @Nested
    inner class Performance {

        private val logger = LoggerFactory.getLogger(Performance::class.java)

        @Test
        fun `serialization and deserialization remain bounded for representative snapshot`() {
            val snapshot = snapshot(
                blackboard = BlackboardSnapshot(
                    blackboardId = "bb-perf",
                    entries = (1..50).map { index ->
                        BlackboardEntrySnapshot(
                            entryId = "bb-perf:$index",
                            sequence = index.toLong(),
                            value = SerializedBlackboardValue(
                                typeName = "test-value",
                                contentType = MediaType.APPLICATION_JSON_VALUE,
                                payload = """{"index":$index,"value":"payload-$index"}""".toByteArray(),
                            ),
                        )
                    },
                    bindings = (1..25).associate { index ->
                        "binding-$index" to BlackboardBindingSnapshot(
                            key = "binding-$index",
                            value = SerializedBlackboardValue(
                                typeName = "test-binding",
                                contentType = MediaType.APPLICATION_JSON_VALUE,
                                payload = """{"index":$index,"value":"binding-$index"}""".toByteArray(),
                            ),
                        )
                    },
                ),
            )
            val iterations = 250
            val serialized = serializer.serialize(snapshot)

            val serializeNanos = measureNanoTime {
                repeat(iterations) {
                    serializer.serialize(snapshot)
                }
            }
            val deserializeNanos = measureNanoTime {
                repeat(iterations) {
                    serializer.deserialize(serialized)
                }
            }

            val serializeAverageMicros = serializeNanos / iterations / 1_000
            val deserializeAverageMicros = deserializeNanos / iterations / 1_000
            logger.info(
                "AgentProcessSnapshot JSON performance: payloadBytes={}, iterations={}, serializeAvgMicros={}, deserializeAvgMicros={}",
                serialized.payload.size,
                iterations,
                serializeAverageMicros,
                deserializeAverageMicros,
            )

            assertTrue(serialized.payload.isNotEmpty())
            assertTrue(serializeAverageMicros < 10_000, "Average snapshot serialization should stay below 10ms")
            assertTrue(deserializeAverageMicros < 10_000, "Average snapshot deserialization should stay below 10ms")
        }
    }

    private fun snapshot(
        blackboard: BlackboardSnapshot = BlackboardSnapshot(
            blackboardId = "bb-1",
            entries = emptyList(),
            bindings = emptyMap(),
        ),
    ): AgentProcessSnapshot =
        AgentProcessSnapshot(
            processId = "p1",
            parentId = "parent",
            agentName = "test-agent",
            processImplementationClassName = "test-process",
            status = AgentProcessStatusCode.WAITING,
            version = 3,
            timestamp = createdAt,
            processOptions = ProcessOptionsSnapshot.from(com.embabel.agent.core.ProcessOptions()),
            blackboard = blackboard,
            pendingAwaitableId = "awaitable-1",
            metadata = mapOf("schema" to "test"),
        )
}
