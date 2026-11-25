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

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.AbstractAwaitable
import com.embabel.agent.core.hitl.AwaitableResponse
import com.embabel.agent.core.hitl.ResponseImpact
import com.embabel.agent.core.hitl.waitFor
import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.persistence.AgentProcessPersistence
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
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
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

// ─── Domain model ────────────────────────────────────────────────────────────
// Identical to WaitForMvcIntegrationTest and HitlJCachePersistenceMvcIntegrationTest.
// Defined inline so this module has no test-source dependency on embabel-agent-api.

private data class ChoiceRequest(val prompt: String, val options: List<String>)

private data class UserChoice(val value: String, val request: ChoiceRequest? = null)

private data class AdventureResult(val outcome: String)

private class ChoiceAwaitable(
    val choiceRequest: ChoiceRequest,
    id: String = UUID.randomUUID().toString(),
) : AbstractAwaitable<UserChoice, UserChoiceResponse>(
    UserChoice("", choiceRequest),
    id = id,
) {
    override fun onResponse(response: UserChoiceResponse, agentProcess: AgentProcess): ResponseImpact {
        agentProcess.blackboard.addObject(UserChoice(response.choice, choiceRequest))
        return ResponseImpact.UPDATED
    }
}

private data class UserChoiceResponse(
    override val id: String = UUID.randomUUID().toString(),
    override val awaitableId: String,
    val choice: String,
    override val timestamp: Instant = Instant.now(),
) : AwaitableResponse {
    override fun persistent(): Boolean = false
}

@com.embabel.agent.api.annotation.Agent(description = "JDBC adventure agent requiring user choices")
private class AdventureAgent {
    @Action
    fun getChoice(input: UserInput, context: ActionContext): UserChoice =
        waitFor(
            ChoiceAwaitable(
                ChoiceRequest(
                    prompt = "Where do you want to go?",
                    options = listOf("Castle", "Forest", "Cave"),
                )
            )
        )

    @Action
    @AchievesGoal(description = "Complete the JDBC adventure")
    fun processChoice(choice: UserChoice, context: ActionContext): AdventureResult =
        AdventureResult("You chose: ${choice.value}")
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

private data class StartAdventureRequest(val playerName: String)

private data class AwaitableResponseDto(
    val processId: String,
    val awaitableId: String,
    val prompt: String,
    val options: List<String>,
)

private data class ContinueAdventureRequest(val awaitableId: String, val choice: String)

private data class AdventureResultDto(val outcome: String)

// ─── Blackboard serializer ───────────────────────────────────────────────────
// Required for snapshot/restore: ChoiceAwaitable cannot be round-tripped
// by the Jackson fallback because AbstractAwaitable has no no-arg constructor.
// Register one BlackboardEntrySerializer per custom Awaitable or domain type
// that the fallback cannot handle.

private data class ChoiceAwaitableSnapshot(val id: String, val choiceRequest: ChoiceRequest)

private class ChoiceAwaitableSerializer(
    private val objectMapper: ObjectMapper,
) : BlackboardEntrySerializer {

    override fun supportsSerialization(value: Any): Boolean = value is ChoiceAwaitable

    override fun supportsDeserialization(value: SerializedBlackboardValue): Boolean =
        value.typeName == ChoiceAwaitable::class.java.name

    override fun serialize(value: Any, context: BlackboardEntrySerializationContext): SerializedBlackboardValue {
        val awaitable = value as ChoiceAwaitable
        return SerializedBlackboardValue(
            typeName = ChoiceAwaitable::class.java.name,
            contentType = MediaType.APPLICATION_JSON_VALUE,
            payload = objectMapper.writeValueAsBytes(
                ChoiceAwaitableSnapshot(id = awaitable.id, choiceRequest = awaitable.choiceRequest)
            ),
        )
    }

    override fun deserialize(value: SerializedBlackboardValue, context: BlackboardEntryDeserializationContext): Any {
        val snapshot = objectMapper.readValue(value.payload, ChoiceAwaitableSnapshot::class.java)
        return ChoiceAwaitable(choiceRequest = snapshot.choiceRequest, id = snapshot.id)
    }
}

// ─── Controller ──────────────────────────────────────────────────────────────
// Depends only on AgentProcessRepository — backend-agnostic by design.

private val adventureLogger = LoggerFactory.getLogger("HitlJdbcPersistenceMvcController")

@Profile("hitl-jdbc")
@RestController
@RequestMapping("/jdbc-adventure")
private class PersistentAdventureController(
    @Qualifier("hitlJdbcRepository")
    private val processRepository: AgentProcessRepository,
) {
    @PostMapping("/start")
    fun start(@RequestBody request: StartAdventureRequest): ResponseEntity<Any> {
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(UserInput(request.playerName))
        val agent = AgentMetadataReader().createAgentMetadata(AdventureAgent()) as Agent
        val process = SimpleAgentProcess(
            id = UUID.randomUUID().toString(),
            parentId = null,
            agent = agent,
            processOptions = ProcessOptions.DEFAULT,
            blackboard = blackboard,
            platformServices = IntegrationTestUtils.dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            timestamp = Instant.now(),
        )
        val result = process.run()
        processRepository.save(result) // test only — platform calls save automatically
        adventureLogger.info("POST /jdbc-adventure/start processId={} status={}", result.id, result.status)
        return when (result.status) {
            AgentProcessStatusCode.WAITING -> {
                val awaitable = result.blackboard.last(ChoiceAwaitable::class.java)
                    ?: error("Expected ChoiceAwaitable in blackboard")
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
                    ?: error("No AdventureResult in blackboard")
                ResponseEntity.ok(AdventureResultDto(outcome = finalResult.outcome))
            }
            else -> error("Unexpected process status: ${result.status}")
        }
    }

    @PostMapping("/{processId}/continue")
    fun continueProcess(
        @PathVariable processId: String,
        @RequestBody request: ContinueAdventureRequest,
    ): ResponseEntity<Any> {
        val agentProcess = processRepository.findById(processId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found: $processId")
        require(agentProcess.status == AgentProcessStatusCode.WAITING) {
            "Process is not waiting: ${agentProcess.status}"
        }
        val awaitable = agentProcess.blackboard.last(ChoiceAwaitable::class.java)
            ?: error("Expected ChoiceAwaitable in blackboard")
        require(awaitable.id == request.awaitableId) {
            "Awaitable ID mismatch: expected ${awaitable.id}, got ${request.awaitableId}"
        }
        awaitable.onResponse(UserChoiceResponse(awaitableId = request.awaitableId, choice = request.choice), agentProcess)
        val resumed = agentProcess.run()
        processRepository.save(resumed) // test only — platform calls save automatically
        adventureLogger.info("POST /jdbc-adventure/{}/continue status={}", processId, resumed.status)
        return when (resumed.status) {
            AgentProcessStatusCode.WAITING -> {
                val next = resumed.blackboard.last(ChoiceAwaitable::class.java)
                    ?: error("Expected ChoiceAwaitable in blackboard")
                ResponseEntity.ok(
                    AwaitableResponseDto(
                        processId = resumed.id,
                        awaitableId = next.id,
                        prompt = next.choiceRequest.prompt,
                        options = next.choiceRequest.options,
                    )
                )
            }
            AgentProcessStatusCode.COMPLETED -> {
                val finalResult = resumed.blackboard.last(AdventureResult::class.java)
                    ?: error("No AdventureResult in blackboard")
                ResponseEntity.ok(AdventureResultDto(outcome = finalResult.outcome))
            }
            else -> error("Unexpected process status: ${resumed.status}")
        }
    }
}

// ─── Spring Boot application ──────────────────────────────────────────────────

@Configuration
@ComponentScan(basePackages = ["com.embabel.agent.jdbc"])
@EnableAutoConfiguration
@Profile("hitl-jdbc")
private class HitlJdbcPersistenceTestApplication

// ─── Test ─────────────────────────────────────────────────────────────────────

/**
 * Reference implementation: HITL agent flow backed by [JdbcAgentProcessSnapshotStore].
 *
 * ## What this proves
 *
 * 1. A process that reaches `WAITING` is durably checkpointed to the database
 *    at version 1.
 * 2. After the runtime repository is cleared (simulating a pod restart or
 *    scale-out), a `/continue` call restores the process from the JDBC snapshot
 *    and resumes it exactly where it paused.
 * 3. The completed snapshot is written back to the database at version 2.
 * 4. The client-held `awaitableId` survives the serialize/deserialize cycle —
 *    the id in the `/continue` request matches the restored awaitable exactly.
 *    This proves that [ChoiceAwaitableSerializer] preserves awaitable identity.
 *
 * **Note on `save` calls:** the test controller calls `processRepository.save()`
 * explicitly to simulate what the platform's execution engine does automatically.
 * Production agents do not call `save` directly — the platform's checkpoint
 * policy drives all persistence writes.
 *
 * ## How to adapt this for production
 *
 * The [HitlJdbcPersistenceTestConfig] below is your starting point.
 * Replace the H2 [DataSource] with the one for your target database — everything
 * else stays identical:
 *
 * ```
 * // PostgreSQL ───────────────────────────────────────────────────────────────
 * @Bean
 * fun dataSource(): DataSource = PGSimpleDataSource().apply {
 *     setURL("jdbc:postgresql://localhost:5432/mydb")
 *     user = "app"
 *     password = System.getenv("DB_PASSWORD")
 * }
 *
 * // HikariCP (recommended for production) ───────────────────────────────────
 * @Bean
 * fun dataSource(): DataSource = HikariDataSource(HikariConfig().apply {
 *     jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
 *     username = "app"
 *     password = System.getenv("DB_PASSWORD")
 * })
 * ```
 *
 * Apply [schema.sql][com.embabel.agent.jdbc] to your database before starting,
 * or configure Flyway/Liquibase to manage the migration.
 *
 * The [jdbcRuntimeRepository], [jdbcSnapshotStore] and [agentProcessRepository]
 * beans are identical regardless of the underlying database.
 *
 * @see HitlJCachePersistenceMvcIntegrationTest for the cache-backed
 * equivalent.
 */
@SpringBootTest(classes = [HitlJdbcPersistenceTestApplication::class])
@ActiveProfiles("test", "hitl-jdbc")
@TestPropertySource(properties = ["embabel.agent.platform.persistence.enabled=true"])
@AutoConfigureMockMvc
class HitlJdbcPersistenceMvcIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    @Qualifier("jdbcRuntimeRepository")
    private lateinit var runtimeRepository: InMemoryAgentProcessRepository

    @Autowired
    @Qualifier("hitlJdbcTemplate")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Qualifier("hitlJdbcSnapshotStore")
    private lateinit var snapshotStore: AgentProcessSnapshotStore

    private val objectMapper: ObjectMapper = EmbabelObjectMapperHolder.createDefault().get()

    @BeforeEach
    fun setUp() {
        runtimeRepository.clear()
        jdbcTemplate.clearSnapshots()
    }

    @Test
    fun `complete adventure flow resumes from jdbc snapshot after runtime repository loss`() {
        // ── Step 1: start the adventure ───────────────────────────────────────
        val startResult = mockMvc.perform(
            MockMvcRequestBuilders.post("/jdbc-adventure/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(StartAdventureRequest("Player1")))
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.processId").exists())
            .andExpect(MockMvcResultMatchers.jsonPath("$.awaitableId").exists())
            .andExpect(MockMvcResultMatchers.jsonPath("$.prompt").value("Where do you want to go?"))
            .andReturn()

        val awaitableDto = objectMapper.readValue(
            startResult.response.contentAsString,
            AwaitableResponseDto::class.java,
        )

        // Process is in the runtime repo and snapshot is in the DB at version 1.
        assertThat(runtimeRepository.findById(awaitableDto.processId)?.status)
            .isEqualTo(AgentProcessStatusCode.WAITING)
        assertThat(snapshotStore.findLatestByProcessId(awaitableDto.processId)?.version).isEqualTo(1L)
        adventureLogger.info("Step 1 complete: durable snapshots={}", jdbcTemplate.snapshotRows())

        // ── Step 2: simulate pod loss ─────────────────────────────────────────
        runtimeRepository.clear()
        assertThat(runtimeRepository.findById(awaitableDto.processId)).isNull()

        // ── Step 3: continue — PersistentAgentProcessRepository restores from DB
        mockMvc.perform(
            MockMvcRequestBuilders.post("/jdbc-adventure/${awaitableDto.processId}/continue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ContinueAdventureRequest(
                            awaitableId = awaitableDto.awaitableId,
                            choice = "Castle",
                        )
                    )
                )
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.outcome").value("You chose: Castle"))

        // Completed process is back in the runtime repo and snapshot version is 2.
        assertThat(runtimeRepository.findById(awaitableDto.processId)?.status)
            .isEqualTo(AgentProcessStatusCode.COMPLETED)
        assertThat(snapshotStore.findLatestByProcessId(awaitableDto.processId)?.version).isEqualTo(2L)
        assertThat(snapshotStore.findLatestByProcessId(awaitableDto.processId)?.status)
            .isEqualTo(AgentProcessStatusCode.COMPLETED)
        adventureLogger.info("Step 3 complete: durable snapshots={}", jdbcTemplate.snapshotRows())
    }

    // ─── Reference @Bean configuration ───────────────────────────────────────
    /**
     * Reference configuration for JDBC-backed agent process persistence.
     *
     * Copy these beans into your `@Configuration` class and swap the
     * [dataSource] bean for the backend of your choice. Every other bean
     * is identical regardless of database vendor.
     */
    @TestConfiguration
    @Profile("hitl-jdbc")
    class HitlJdbcPersistenceTestConfig {

        // ── STEP 1: choose your DataSource ────────────────────────────────────
        //
        // This is the ONLY bean that changes between databases.
        //
        // PostgreSQL with HikariCP (recommended for production):
        //   @Bean
        //   fun dataSource(): DataSource = HikariDataSource(HikariConfig().apply {
        //       jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        //       username = "app"
        //       password = System.getenv("DB_PASSWORD")
        //   })
        //
        // MySQL / MariaDB:
        //   @Bean
        //   fun dataSource(): DataSource = HikariDataSource(HikariConfig().apply {
        //       jdbcUrl = "jdbc:mysql://localhost:3306/mydb"
        //       username = "app"
        //       password = System.getenv("DB_PASSWORD")
        //   })
        // ─────────────────────────────────────────────────────────────────────
        @Bean
        fun hitlJdbcDataSource(): DataSource =
            DriverManagerDataSource(
                "jdbc:h2:mem:hitl-jdbc;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "sa",
                "",
            )

        // ── STEP 2: expose the JdbcTemplate ───────────────────────────────────
        //
        // Named so the test can inject it directly for schema init and cleanup.
        // Spring Boot auto-creates a JdbcTemplate from your DataSource in
        // production; you do not need this bean outside tests.
        @Bean
        fun hitlJdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

        // ── STEP 3: build the snapshot store ──────────────────────────────────
        //
        // Apply schema.sql to your database before starting, or configure
        // Flyway/Liquibase to manage the migration. The store itself does not
        // create the table.
        @Bean
        fun hitlJdbcSnapshotStore(jdbcTemplate: JdbcTemplate): AgentProcessSnapshotStore {
            jdbcTemplate.initSchema()
            return JdbcAgentProcessSnapshotStore(jdbcTemplate)
        }

        // ── STEP 4: expose the runtime repository ─────────────────────────────
        //
        // Named so the test can inject and clear it to simulate a pod restart.
        // In production you do not need a named qualifier.
        @Bean
        fun jdbcRuntimeRepository(): InMemoryAgentProcessRepository = InMemoryAgentProcessRepository()

        // ── STEP 5: assemble the durable repository ───────────────────────────
        //
        // AgentProcessPersistence.persistentRepository() is the stable public
        // factory. It hides snapshot, serialisation and restore internals so they
        // can evolve without affecting your configuration.
        //
        // blackboardEntrySerializers: supply one BlackboardEntrySerializer for
        // each custom Awaitable or domain object that the Jackson fallback cannot
        // round-trip (types with no no-arg constructor, sealed classes, etc.).
        // Simple data classes are handled automatically by the Jackson fallback.
        @Bean
        @Qualifier("hitlJdbcRepository")
        fun agentProcessRepository(
            @Qualifier("jdbcRuntimeRepository") runtimeRepository: InMemoryAgentProcessRepository,
            @Qualifier("hitlJdbcSnapshotStore") snapshotStore: AgentProcessSnapshotStore,
        ): AgentProcessRepository {
            val objectMapper = EmbabelObjectMapperHolder.createDefault().get()
            val agent = AgentMetadataReader().createAgentMetadata(AdventureAgent()) as Agent
            return AgentProcessPersistence.persistentRepository(
                runtimeRepository = runtimeRepository,
                snapshotStore = snapshotStore,
                objectMapper = objectMapper,
                agents = { listOf(agent) },
                platformServices = { IntegrationTestUtils.dummyPlatformServices() },
                blackboardEntrySerializers = listOf(ChoiceAwaitableSerializer(objectMapper)),
            )
        }
    }
}

// ─── Local test extensions ────────────────────────────────────────────────────

private fun JdbcTemplate.initSchema() {
    execute(
        """
        create table if not exists agent_process_snapshots (
            process_id   varchar(255)        not null,
            parent_id    varchar(255),
            agent_name   varchar(255)        not null,
            status       varchar(64)         not null,
            version      bigint              not null,
            content_type varchar(255)        not null,
            payload      binary large object not null,
            created_at   timestamp           not null,
            updated_at   timestamp           not null,
            constraint pk_agent_process_snapshots primary key (process_id)
        )
        """.trimIndent()
    )
}

private fun JdbcTemplate.clearSnapshots() {
    update("delete from agent_process_snapshots")
}

private fun JdbcTemplate.snapshotRows(): List<Map<String, Any?>> =
    queryForList(
        """
        select process_id, status, version, length(payload) as payload_bytes
        from agent_process_snapshots
        order by process_id
        """.trimIndent()
    )
