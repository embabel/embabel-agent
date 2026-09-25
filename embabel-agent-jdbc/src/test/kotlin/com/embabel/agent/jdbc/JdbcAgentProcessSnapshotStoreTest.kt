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
package com.embabel.agent.jdbc

import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.time.temporal.ChronoUnit

class JdbcAgentProcessSnapshotStoreTest {

    private val dataSource = DriverManagerDataSource(
        "jdbc:h2:mem:agent-snapshot-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "sa",
        "",
    )
    private val jdbcTemplate = JdbcTemplate(dataSource)
    private val store = JdbcAgentProcessSnapshotStore(jdbcTemplate)

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute(
            """
            create table if not exists agent_process_snapshots (
                process_id   varchar(255) not null,
                parent_id    varchar(255),
                agent_name   varchar(255) not null,
                status       varchar(64)  not null,
                version      bigint       not null,
                content_type varchar(255) not null,
                payload      binary large object not null,
                created_at   timestamp    not null,
                updated_at   timestamp    not null,
                constraint pk_agent_process_snapshots primary key (process_id)
            )
            """.trimIndent()
        )
        jdbcTemplate.update("delete from agent_process_snapshots")
    }

    @Test
    fun `save inserts new snapshot and findLatestByProcessId returns it`() {
        val snapshot = snapshot("p1", AgentProcessStatusCode.WAITING, version = 1)
        store.save(snapshot, expectedVersion = null)

        val found = store.findLatestByProcessId("p1")
        assertThat(found).isNotNull
        assertThat(found!!.processId).isEqualTo("p1")
        assertThat(found.status).isEqualTo(AgentProcessStatusCode.WAITING)
        assertThat(found.version).isEqualTo(1L)
        assertThat(found.payload).isEqualTo(snapshot.payload)
    }

    @Test
    fun `findLatestByProcessId returns null for unknown process`() {
        assertThat(store.findLatestByProcessId("unknown")).isNull()
    }

    @Test
    fun `save with null expectedVersion fails when snapshot already exists`() {
        store.save(snapshot("p1", AgentProcessStatusCode.WAITING, version = 1), expectedVersion = null)

        assertThatThrownBy {
            store.save(snapshot("p1", AgentProcessStatusCode.WAITING, version = 2), expectedVersion = null)
        }.isInstanceOf(AgentProcessPersistenceException::class.java)
            .hasMessageContaining("p1")
    }

    @Test
    fun `save with matching expectedVersion updates snapshot`() {
        store.save(snapshot("p1", AgentProcessStatusCode.WAITING, version = 1), expectedVersion = null)

        val updated = snapshot("p1", AgentProcessStatusCode.COMPLETED, version = 2)
        store.save(updated, expectedVersion = 1L)

        val found = store.findLatestByProcessId("p1")
        assertThat(found!!.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
        assertThat(found.version).isEqualTo(2L)
    }

    @Test
    fun `save with stale expectedVersion throws AgentProcessPersistenceException`() {
        store.save(snapshot("p1", AgentProcessStatusCode.WAITING, version = 1), expectedVersion = null)
        store.save(snapshot("p1", AgentProcessStatusCode.COMPLETED, version = 2), expectedVersion = 1L)

        assertThatThrownBy {
            store.save(snapshot("p1", AgentProcessStatusCode.COMPLETED, version = 3), expectedVersion = 1L)
        }.isInstanceOf(AgentProcessPersistenceException::class.java)
            .hasMessageContaining("p1")
            .hasMessageContaining("1")
    }

    @Test
    fun `findByParentId returns all child snapshots`() {
        store.save(snapshot("child1", AgentProcessStatusCode.COMPLETED, version = 1, parentId = "parent1"), null)
        store.save(snapshot("child2", AgentProcessStatusCode.WAITING, version = 1, parentId = "parent1"), null)
        store.save(snapshot("other", AgentProcessStatusCode.RUNNING, version = 1, parentId = "parent2"), null)

        val children = store.findByParentId("parent1")
        assertThat(children).hasSize(2)
        assertThat(children.map { it.processId }).containsExactlyInAnyOrder("child1", "child2")
    }

    @Test
    fun `findByParentId returns empty list when no children`() {
        assertThat(store.findByParentId("no-children")).isEmpty()
    }

    @Test
    fun `delete removes the snapshot`() {
        store.save(snapshot("p1", AgentProcessStatusCode.COMPLETED, version = 1), null)
        store.delete("p1")
        assertThat(store.findLatestByProcessId("p1")).isNull()
    }

    @Test
    fun `delete is idempotent for unknown process`() {
        store.delete("nonexistent")
    }

    @Test
    fun `StoredSnapshotMetadata reflects saved version and timestamp`() {
        val snapshot = snapshot("p1", AgentProcessStatusCode.WAITING, version = 1)
        val metadata = store.save(snapshot, expectedVersion = null)

        assertThat(metadata.processId).isEqualTo("p1")
        assertThat(metadata.version).isEqualTo(1L)
        assertThat(metadata.updatedAt).isEqualTo(snapshot.updatedAt)
    }

    private fun snapshot(
        processId: String,
        status: AgentProcessStatusCode,
        version: Long,
        parentId: String? = null,
    ) = com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot(
        processId = processId,
        parentId = parentId,
        agentName = "TestAgent",
        status = status,
        version = version,
        contentType = MediaType.APPLICATION_JSON,
        payload = """{"processId":"$processId"}""".toByteArray(Charsets.UTF_8),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    )
}
