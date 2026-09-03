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
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import java.time.Instant

class InMemoryAgentProcessSnapshotStoreTest {

    private val now = Instant.parse("2026-08-28T00:00:00Z")

    @Test
    fun `saves and loads snapshot`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val snapshot = snapshot(version = 1)

        val stored = store.save(snapshot)

        assertEquals("p1", stored.processId)
        assertEquals(1, stored.version)
        assertEquals(now, stored.updatedAt)

        val loaded = store.findLatestByProcessId("p1")
        assertSnapshotFields(snapshot, loaded)
        assertArrayEquals(snapshot.payload, loaded?.payload)
    }

    @Test
    fun `rejects create when snapshot already exists`() {
        val store = InMemoryAgentProcessSnapshotStore()
        store.save(snapshot(version = 1))

        assertThrows(AgentProcessPersistenceException::class.java) {
            store.save(snapshot(version = 2))
        }
    }

    @Test
    fun `updates when expected version matches`() {
        val store = InMemoryAgentProcessSnapshotStore()
        store.save(snapshot(version = 1))

        val updated = snapshot(version = 2, payload = """{"state":"updated"}""")
        val stored = store.save(updated, expectedVersion = 1)

        assertEquals(2, stored.version)
        val loaded = store.findLatestByProcessId("p1")
        assertSnapshotFields(updated, loaded)
        assertArrayEquals(updated.payload, loaded?.payload)
    }

    @Test
    fun `rejects update when expected version is stale`() {
        val store = InMemoryAgentProcessSnapshotStore()
        store.save(snapshot(version = 1))
        store.save(snapshot(version = 2), expectedVersion = 1)

        assertThrows(AgentProcessPersistenceException::class.java) {
            store.save(snapshot(version = 3), expectedVersion = 1)
        }
    }

    @Test
    fun `deletes snapshot`() {
        val store = InMemoryAgentProcessSnapshotStore()
        store.save(snapshot(version = 1))

        store.delete("p1")

        assertNull(store.findLatestByProcessId("p1"))
    }

    @Test
    fun `finds snapshots by parent id`() {
        val store = InMemoryAgentProcessSnapshotStore()
        store.save(snapshot(processId = "parent", version = 1))
        store.save(snapshot(processId = "child-1", parentId = "parent", version = 1))
        store.save(snapshot(processId = "child-2", parentId = "parent", version = 1))
        store.save(snapshot(processId = "other-child", parentId = "other-parent", version = 1))

        val children = store.findByParentId("parent")

        assertEquals(setOf("child-1", "child-2"), children.map { it.processId }.toSet())
    }

    private fun snapshot(
        processId: String = "p1",
        parentId: String? = null,
        version: Long,
        payload: String = """{"state":"waiting"}""",
    ) = SerializedAgentProcessSnapshot(
        processId = processId,
        parentId = parentId,
        agentName = "test-agent",
        status = AgentProcessStatusCode.WAITING,
        version = version,
        contentType = MediaType.APPLICATION_JSON,
        payload = payload.toByteArray(),
        createdAt = now,
        updatedAt = now,
    )

    private fun assertSnapshotFields(
        expected: SerializedAgentProcessSnapshot,
        actual: SerializedAgentProcessSnapshot?,
    ) {
        requireNotNull(actual)
        assertEquals(expected.processId, actual.processId)
        assertEquals(expected.parentId, actual.parentId)
        assertEquals(expected.agentName, actual.agentName)
        assertEquals(expected.status, actual.status)
        assertEquals(expected.version, actual.version)
        assertEquals(expected.contentType, actual.contentType)
        assertEquals(expected.createdAt, actual.createdAt)
        assertEquals(expected.updatedAt, actual.updatedAt)
        assertEquals(expected.metadata, actual.metadata)
    }
}
