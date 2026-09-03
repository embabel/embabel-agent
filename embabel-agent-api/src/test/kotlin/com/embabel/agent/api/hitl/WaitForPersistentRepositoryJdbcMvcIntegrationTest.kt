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
package com.embabel.agent.api.hitl

import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import com.embabel.agent.spi.persistence.StoredSnapshotMetadata
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.spi.support.persistence.AgentProcessSnapshotFactory
import com.embabel.agent.spi.support.persistence.AgentProcessSnapshotRestorer
import com.embabel.agent.spi.support.persistence.BlackboardEntrySerializerResolver
import com.embabel.agent.spi.support.persistence.InMemoryBlackboardSnapshotter
import com.embabel.agent.spi.support.persistence.JacksonAgentProcessStateSerializer
import com.embabel.agent.spi.support.persistence.JacksonBlackboardEntrySerializer
import com.embabel.agent.spi.support.persistence.LifecycleCheckpointPolicy
import com.embabel.agent.spi.support.persistence.PersistentAgentProcessRepository
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.common.util.EmbabelObjectMapperHolder
import tools.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val persistentMvcTestLogger = LoggerFactory.getLogger("WaitForPersistentRepositoryJdbcMvcIntegrationTest")

/**
 * MVC fixture for a HITL flow backed by the configured [AgentProcessRepository].
 *
 * The controller deliberately depends only on the repository interface. Snapshot
 * save/restore is exercised indirectly by the test configuration, which wires
 * this dependency as [PersistentAgentProcessRepository].
 */
@ConditionalOnProperty(name = ["waitfor.persistent.mvc.test.enabled"], havingValue = "true")
@Profile("waitfor-persistent-jdbc")
@RestController
@RequestMapping("/persistent-adventure")
class PersistentAdventureController(
    @param:Qualifier("waitForPersistentRepository")
    private val processRepository: AgentProcessRepository,
) {

    @PostMapping("/start")
    fun start(@RequestBody request: StartAdventureRequest): ResponseEntity<Any> {
        persistentMvcTestLogger.info("POST /persistent-adventure/start player={}", request.playerName)
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(UserInput(request.playerName))

        val agent = com.embabel.agent.api.annotation.support.AgentMetadataReader()
            .createAgentMetadata(AdventureAgent()) as com.embabel.agent.core.Agent

        val agentProcess = SimpleAgentProcess(
            id = UUID.randomUUID().toString(),
            parentId = null,
            agent = agent,
            processOptions = ProcessOptions.DEFAULT,
            blackboard = blackboard,
            platformServices = IntegrationTestUtils.dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            timestamp = Instant.now(),
        )

        val result = agentProcess.run()
        // The injected repository is PersistentAgentProcessRepository, so this
        // save writes to the runtime repository and stores a JDBC snapshot when
        // the process reaches a configured persistence boundary.
        processRepository.save(result)
        persistentMvcTestLogger.info(
            "POST /persistent-adventure/start processId={} status={}",
            result.id,
            result.status,
        )

        return when (result.status) {
            AgentProcessStatusCode.WAITING -> {
                val awaitable = result.blackboard.last(ChoiceAwaitable::class.java)
                    ?: throw IllegalStateException("Expected ChoiceAwaitable in blackboard")
                persistentMvcTestLogger.info(
                    "POST /persistent-adventure/start returning awaitable processId={} awaitableId={} options={}",
                    result.id,
                    awaitable.id,
                    awaitable.choiceRequest.options,
                )
                ResponseEntity.ok(
                    AwaitableResponseDto(
                        processId = result.id,
                        awaitableId = awaitable.id,
                        prompt = awaitable.choiceRequest.prompt,
                        options = awaitable.choiceRequest.options,
                    )
                )
            }

            AgentProcessStatusCode.COMPLETED -> {
                val finalResult = result.blackboard.last(AdventureResult::class.java)
                    ?: throw IllegalStateException("No result found")
                ResponseEntity.ok(AdventureResultDto(outcome = finalResult.outcome))
            }

            else -> throw IllegalStateException("Unexpected process status: ${result.status}")
        }
    }

    @PostMapping("/{processId}/continue")
    fun continueProcess(
        @PathVariable processId: String,
        @RequestBody request: ContinueAdventureRequest,
    ): ResponseEntity<Any> {
        persistentMvcTestLogger.info(
            "POST /persistent-adventure/{}/continue awaitableId={} choice={}",
            processId,
            request.awaitableId,
            request.choice,
        )
        // The test clears the runtime repository before calling /continue.
        // PersistentAgentProcessRepository therefore misses in memory, loads the
        // JDBC snapshot, restores the process, and saves it back to the runtime
        // repository before returning it here.
        val agentProcess = processRepository.findById(processId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found: $processId")
        persistentMvcTestLogger.info(
            "POST /persistent-adventure/{}/continue loaded process status={} historySize={}",
            processId,
            agentProcess.status,
            agentProcess.history.size,
        )

        require(agentProcess.status == AgentProcessStatusCode.WAITING) {
            "Process is not waiting: ${agentProcess.status}"
        }

        val awaitable = agentProcess.blackboard.last(ChoiceAwaitable::class.java)
            ?: throw IllegalStateException("Expected ChoiceAwaitable in blackboard")
        persistentMvcTestLogger.info(
            "POST /persistent-adventure/{}/continue restored awaitableId={}",
            processId,
            awaitable.id,
        )
        require(awaitable.id == request.awaitableId) {
            "Awaitable ID mismatch: expected ${awaitable.id}, got ${request.awaitableId}"
        }

        awaitable.onResponse(
            UserChoiceResponse(
                awaitableId = request.awaitableId,
                choice = request.choice,
            ),
            agentProcess,
        )

        val resumedProcess = agentProcess.run()
        processRepository.save(resumedProcess)
        persistentMvcTestLogger.info(
            "POST /persistent-adventure/{}/continue resumed status={}",
            processId,
            resumedProcess.status,
        )

        return when (resumedProcess.status) {
            AgentProcessStatusCode.WAITING -> {
                val nextAwaitable = resumedProcess.blackboard.last(ChoiceAwaitable::class.java)
                    ?: throw IllegalStateException("Expected ChoiceAwaitable in blackboard")
                ResponseEntity.ok(
                    AwaitableResponseDto(
                        processId = resumedProcess.id,
                        awaitableId = nextAwaitable.id,
                        prompt = nextAwaitable.choiceRequest.prompt,
                        options = nextAwaitable.choiceRequest.options,
                    )
                )
            }

            AgentProcessStatusCode.COMPLETED -> {
                val finalResult = resumedProcess.blackboard.last(AdventureResult::class.java)
                    ?: throw IllegalStateException("No result found")
                ResponseEntity.ok(AdventureResultDto(outcome = finalResult.outcome))
            }

            else -> throw IllegalStateException("Unexpected process status: ${resumedProcess.status}")
        }
    }
}

@Configuration
@ComponentScan(basePackages = ["com.embabel.agent.api.hitl"])
@EnableAutoConfiguration
@Profile("waitfor-persistent-jdbc")
class WaitForPersistentRepositoryJdbcTestApplication

@SpringBootTest(classes = [WaitForPersistentRepositoryJdbcTestApplication::class])
@ActiveProfiles("test", "waitfor-persistent-jdbc")
@TestPropertySource(properties = ["waitfor.persistent.mvc.test.enabled=true"])
@AutoConfigureMockMvc
class WaitForPersistentRepositoryJdbcMvcIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    @Qualifier("waitForPersistentRuntimeRepository")
    private lateinit var runtimeRepository: InMemoryAgentProcessRepository

    @Autowired
    @Qualifier("waitForPersistentSnapshotStore")
    private lateinit var snapshotStore: AgentProcessSnapshotStore

    private val objectMapper: ObjectMapper = EmbabelObjectMapperHolder.createDefault().get()

    @BeforeEach
    fun setUp() {
        runtimeRepository.clear()
        (snapshotStore as JdbcAgentProcessSnapshotStoreForTest).clear()
        persistentMvcTestLogger.info("Test setup cleared runtime repository and JDBC snapshot table")
    }

    @Test
    fun `complete adventure flow resumes from jdbc snapshot after runtime repository loss`() {
        persistentMvcTestLogger.info("Test step 1: calling POST /persistent-adventure/start")
        val startResponse = mockMvc.perform(
            MockMvcRequestBuilders.post("/persistent-adventure/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(StartAdventureRequest(playerName = "Player1")))
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.processId").exists())
            .andExpect(MockMvcResultMatchers.jsonPath("$.awaitableId").exists())
            .andExpect(MockMvcResultMatchers.jsonPath("$.prompt").value("Where do you want to go?"))
            .andReturn()

        val awaitableResponse = objectMapper.readValue(
            startResponse.response.contentAsString,
            AwaitableResponseDto::class.java,
        )

        assertThat(runtimeRepository.findById(awaitableResponse.processId)?.status)
            .isEqualTo(AgentProcessStatusCode.WAITING)
        assertThat(snapshotStore.findLatestByProcessId(awaitableResponse.processId)).isNotNull
        persistentMvcTestLogger.info(
            "Test step 1 complete: durable snapshots={}",
            (snapshotStore as JdbcAgentProcessSnapshotStoreForTest).describeRows(),
        )

        persistentMvcTestLogger.info(
            "Test step 2: clearing runtime repository for processId={} to simulate pod loss",
            awaitableResponse.processId,
        )
        runtimeRepository.clear()
        assertThat(runtimeRepository.findById(awaitableResponse.processId)).isNull()

        persistentMvcTestLogger.info(
            "Test step 3: calling POST /persistent-adventure/{}/continue after runtime repository loss",
            awaitableResponse.processId,
        )
        mockMvc.perform(
            MockMvcRequestBuilders.post("/persistent-adventure/${awaitableResponse.processId}/continue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ContinueAdventureRequest(
                            awaitableId = awaitableResponse.awaitableId,
                            choice = "Castle",
                        )
                    )
                )
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.outcome").value("You chose: Castle"))

        val completedProcess = runtimeRepository.findById(awaitableResponse.processId)
        assertThat(completedProcess?.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
        assertThat(completedProcess?.blackboard?.last(AdventureResult::class.java)?.outcome)
            .isEqualTo("You chose: Castle")
        val completedSnapshot = snapshotStore.findLatestByProcessId(awaitableResponse.processId)
        assertThat(completedSnapshot?.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
        assertThat(completedSnapshot?.version).isEqualTo(2)
        persistentMvcTestLogger.info(
            "Test step 3 complete: runtime process status={} durable snapshots={}",
            completedProcess?.status,
            (snapshotStore as JdbcAgentProcessSnapshotStoreForTest).describeRows(),
        )
    }

    @TestConfiguration
    @Profile("waitfor-persistent-jdbc")
    class WaitForPersistentRepositoryJdbcTestConfig {

        @Bean
        fun waitForPersistentRuntimeRepository(): InMemoryAgentProcessRepository =
            InMemoryAgentProcessRepository()

        @Bean
        fun waitForPersistentDataSource(): DataSource =
            DriverManagerDataSource(
                "jdbc:h2:mem:waitfor-persistent-jdbc;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "sa",
                "",
            )

        @Bean
        fun waitForPersistentSnapshotStore(dataSource: DataSource): AgentProcessSnapshotStore =
            JdbcAgentProcessSnapshotStoreForTest(JdbcTemplate(dataSource)).apply {
                initializeSchema()
            }

        @Bean
        fun waitForPersistentRepository(
            @Qualifier("waitForPersistentRuntimeRepository")
            runtimeRepository: InMemoryAgentProcessRepository,
            @Qualifier("waitForPersistentSnapshotStore")
            snapshotStore: AgentProcessSnapshotStore,
        ): AgentProcessRepository {
            val objectMapper = EmbabelObjectMapperHolder.createDefault().get()
            val blackboardSnapshotter = InMemoryBlackboardSnapshotter(
                BlackboardEntrySerializerResolver(
                    serializers = listOf(ChoiceAwaitableSerializerForTest(objectMapper)),
                    fallback = JacksonBlackboardEntrySerializer(objectMapper),
                )
            )
            // This E2E proves snapshot restore with an in-memory runtime
            // repository. Transactional runtime+snapshot semantics become
            // possible when the runtime repository is also backed by a
            // persistent store participating in the same transaction.
            return PersistentAgentProcessRepository(
                runtimeRepository = runtimeRepository,
                snapshotStore = snapshotStore,
                checkpointPolicy = LifecycleCheckpointPolicy,
                snapshotFactory = AgentProcessSnapshotFactory(blackboardSnapshotter),
                snapshotSerializer = JacksonAgentProcessStateSerializer(objectMapper),
                snapshotRestorer = AgentProcessSnapshotRestorer(blackboardSnapshotter),
                agents = {
                    listOf(
                        com.embabel.agent.api.annotation.support.AgentMetadataReader()
                            .createAgentMetadata(AdventureAgent()) as com.embabel.agent.core.Agent
                    )
                },
                platformServices = { IntegrationTestUtils.dummyPlatformServices() },
            )
        }
    }
}

private class JdbcAgentProcessSnapshotStoreForTest(
    private val jdbcTemplate: JdbcTemplate,
) : AgentProcessSnapshotStore {

    // For this H2 fixture, each JdbcTemplate.update(...) is a single statement with
    // autocommit, which is enough for insert/update of one snapshot row.

    fun initializeSchema() {
        jdbcTemplate.execute(
            """
            create table if not exists agent_process_snapshots (
                process_id varchar(255) primary key,
                parent_id varchar(255),
                agent_name varchar(255) not null,
                status varchar(64) not null,
                version bigint not null,
                content_type varchar(255) not null,
                payload binary large object not null,
                created_at timestamp not null,
                updated_at timestamp not null
            )
            """.trimIndent()
        )
    }

    fun clear() {
        jdbcTemplate.update("delete from agent_process_snapshots")
        persistentMvcTestLogger.info("JDBC snapshot table cleared")
    }

    fun describeRows(): List<SnapshotRowForLog> {
        return jdbcTemplate.query(
            """
            select process_id, parent_id, agent_name, status, version, content_type,
                   length(payload) as payload_size, created_at, updated_at
            from agent_process_snapshots
            order by process_id
            """.trimIndent()
        ) { rs, _ ->
            SnapshotRowForLog(
                processId = rs.getString("process_id"),
                parentId = rs.getString("parent_id"),
                agentName = rs.getString("agent_name"),
                status = rs.getString("status"),
                version = rs.getLong("version"),
                contentType = rs.getString("content_type"),
                payloadSize = rs.getLong("payload_size"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }

    override fun save(
        snapshot: SerializedAgentProcessSnapshot,
        expectedVersion: Long?,
    ): StoredSnapshotMetadata {
        val updated = if (expectedVersion == null) {
            insert(snapshot)
        } else {
            update(snapshot, expectedVersion)
        }
        if (updated != 1) {
            val found = findLatestByProcessId(snapshot.processId)?.version
            throw AgentProcessPersistenceException(
                "Cannot save snapshot for process [${snapshot.processId}]: expected version " +
                "[$expectedVersion] but found [$found]"
            )
        }
        persistentMvcTestLogger.info(
            "JDBC snapshot saved processId={} status={} expectedVersion={} version={} payloadBytes={}",
            snapshot.processId,
            snapshot.status,
            expectedVersion,
            snapshot.version,
            snapshot.payload.size,
        )
        return StoredSnapshotMetadata(
            processId = snapshot.processId,
            version = snapshot.version,
            updatedAt = snapshot.updatedAt,
        )
    }

    override fun findLatestByProcessId(processId: String): SerializedAgentProcessSnapshot? {
        val snapshot = jdbcTemplate.query(
            """
            select process_id, parent_id, agent_name, status, version, content_type, payload, created_at, updated_at
            from agent_process_snapshots
            where process_id = ?
            """.trimIndent(),
            { rs, _ -> snapshot(rs) },
            processId,
        ).firstOrNull()
        persistentMvcTestLogger.info(
            "JDBC snapshot lookup processId={} result={} version={} status={} payloadBytes={}",
            processId,
            if (snapshot == null) "MISS" else "HIT",
            snapshot?.version,
            snapshot?.status,
            snapshot?.payload?.size,
        )
        return snapshot
    }

    override fun findByParentId(parentId: String): List<SerializedAgentProcessSnapshot> =
        jdbcTemplate.query(
            """
            select process_id, parent_id, agent_name, status, version, content_type, payload, created_at, updated_at
            from agent_process_snapshots
            where parent_id = ?
            order by process_id
            """.trimIndent(),
            { rs, _ -> snapshot(rs) },
            parentId,
        )

    override fun delete(processId: String) {
        jdbcTemplate.update("delete from agent_process_snapshots where process_id = ?", processId)
    }

    private fun insert(snapshot: SerializedAgentProcessSnapshot): Int =
        try {
            jdbcTemplate.update(
                """
                insert into agent_process_snapshots (
                    process_id, parent_id, agent_name, status, version, content_type, payload, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                snapshot.processId,
                snapshot.parentId,
                snapshot.agentName,
                snapshot.status.name,
                snapshot.version,
                snapshot.contentType.toString(),
                snapshot.payload,
                Timestamp.from(snapshot.createdAt),
                Timestamp.from(snapshot.updatedAt),
            )
        } catch (ex: org.springframework.dao.DuplicateKeyException) {
            throw AgentProcessPersistenceException(
                "Cannot create snapshot for process [${snapshot.processId}]: snapshot already exists",
                ex,
            )
        }

    private fun update(
        snapshot: SerializedAgentProcessSnapshot,
        expectedVersion: Long,
    ): Int =
        jdbcTemplate.update(
            """
            update agent_process_snapshots
            set parent_id = ?, agent_name = ?, status = ?, version = ?, content_type = ?,
                payload = ?, created_at = ?, updated_at = ?
            where process_id = ? and version = ?
            """.trimIndent(),
            snapshot.parentId,
            snapshot.agentName,
            snapshot.status.name,
            snapshot.version,
            snapshot.contentType.toString(),
            snapshot.payload,
            Timestamp.from(snapshot.createdAt),
            Timestamp.from(snapshot.updatedAt),
            snapshot.processId,
            expectedVersion,
        )

    private fun snapshot(rs: ResultSet): SerializedAgentProcessSnapshot =
        SerializedAgentProcessSnapshot(
            processId = rs.getString("process_id"),
            parentId = rs.getString("parent_id"),
            agentName = rs.getString("agent_name"),
            status = AgentProcessStatusCode.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            contentType = MediaType.parseMediaType(rs.getString("content_type")),
            payload = rs.getBytes("payload"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}

private class ChoiceAwaitableSerializerForTest(
    private val objectMapper: ObjectMapper,
) : BlackboardEntrySerializer {

    override fun supportsSerialization(value: Any): Boolean =
        value is ChoiceAwaitable

    override fun supportsDeserialization(value: SerializedBlackboardValue): Boolean =
        value.typeName == ChoiceAwaitable::class.java.name

    override fun serialize(
        value: Any,
        context: BlackboardEntrySerializationContext,
    ): SerializedBlackboardValue {
        val awaitable = value as ChoiceAwaitable
        return SerializedBlackboardValue(
            typeName = ChoiceAwaitable::class.java.name,
            contentType = MediaType.APPLICATION_JSON_VALUE,
            payload = objectMapper.writeValueAsBytes(
                ChoiceAwaitableSnapshot(
                    id = awaitable.id,
                    choiceRequest = awaitable.choiceRequest,
                )
            ),
        )
    }

    override fun deserialize(
        value: SerializedBlackboardValue,
        context: BlackboardEntryDeserializationContext,
    ): Any {
        require(MediaType.parseMediaType(value.contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            "Unsupported blackboard value content type [${value.contentType}]"
        }
        val snapshot = objectMapper.readValue(value.payload, ChoiceAwaitableSnapshot::class.java)
        return ChoiceAwaitable(
            choiceRequest = snapshot.choiceRequest,
            id = snapshot.id,
        )
    }
}

private data class ChoiceAwaitableSnapshot(
    val id: String,
    val choiceRequest: ChoiceRequest,
)

private data class SnapshotRowForLog(
    val processId: String,
    val parentId: String?,
    val agentName: String,
    val status: String,
    val version: Long,
    val contentType: String,
    val payloadSize: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)
