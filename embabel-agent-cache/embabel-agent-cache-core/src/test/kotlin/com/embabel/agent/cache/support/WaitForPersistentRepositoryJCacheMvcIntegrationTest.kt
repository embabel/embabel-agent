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
package com.embabel.agent.cache.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.cache.AgentCacheProvider
import com.embabel.agent.cache.CacheRegionConfig
import com.embabel.agent.cache.CacheRegions
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
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
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
import org.springframework.cache.CacheManager
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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

// ─── Domain model ────────────────────────────────────────────────────────────
// Identical to the in-memory and JDBC MVC test fixtures.
// Extracted to a shared base in the planned refactor.

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

@com.embabel.agent.api.annotation.Agent(description = "Cache adventure agent requiring user choices")
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
    @AchievesGoal(description = "Complete the cache adventure")
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

private val adventureLogger = LoggerFactory.getLogger("PersistentAdventureController")

@ConditionalOnProperty(name = ["waitfor.persistent.cache.mvc.test.enabled"], havingValue = "true")
@Profile("waitfor-persistent-cache")
@RestController
@RequestMapping("/cache-adventure")
private class PersistentAdventureController(
    @Qualifier("waitForCacheRepository")
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
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            timestamp = Instant.now(),
        )
        val result = process.run()
        processRepository.save(result)
        adventureLogger.info("POST /cache-adventure/start processId={} status={}", result.id, result.status)
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
        processRepository.save(resumed)
        adventureLogger.info("POST /cache-adventure/{}/continue status={}", processId, resumed.status)
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
@ComponentScan(basePackages = ["com.embabel.agent.cache.support"])
@EnableAutoConfiguration
@Profile("waitfor-persistent-cache")
private class WaitForPersistentRepositoryCacheTestApplication

// ─── Test ─────────────────────────────────────────────────────────────────────

/**
 * Reference implementation: HITL agent flow backed by a Spring [CacheManager].
 *
 * ## What this proves
 *
 * 1. A process that reaches `WAITING` is durably checkpointed to the cache.
 * 2. After the runtime repository is cleared (simulating a pod restart or
 *    scale-out), a `/continue` call restores the process from the snapshot
 *    and resumes it exactly where it paused.
 * 3. The completed snapshot is written back at version 2.
 *
 * ## How to adapt this for production
 *
 * The [WaitForPersistentRepositoryCacheTestConfig] below is your starting
 * point. Replace the [ConcurrentMapCacheManager] with the `CacheManager` for
 * your chosen backend — everything else stays identical:
 *
 * ```
 * // Redis ────────────────────────────────────────────────────────────────────
 * @Bean
 * fun cacheManager(factory: RedisConnectionFactory): CacheManager =
 *     RedisCacheManager.builder(factory)
 *         .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
 *             .entryTtl(Duration.ofHours(1)))
 *         .build()
 *
 * // Hazelcast ────────────────────────────────────────────────────────────────
 * @Bean
 * fun cacheManager(hz: HazelcastInstance): CacheManager =
 *     HazelcastCacheManager(hz)
 *
 * // Ehcache 3 / JCache ───────────────────────────────────────────────────────
 * @Bean
 * fun cacheManager(): CacheManager =
 *     JCacheCacheManager(Caching.getCachingProvider().cacheManager)
 * ```
 *
 * The [agentCacheProvider], [cacheBackedSnapshotStore] and
 * [agentProcessRepository] beans are backend-neutral and do not change.
 *
 * @see WaitForPersistentRepositoryJdbcMvcIntegrationTest for the JDBC
 * equivalent using a hand-rolled SQL snapshot store.
 */
@SpringBootTest(classes = [WaitForPersistentRepositoryCacheTestApplication::class])
@ActiveProfiles("test", "waitfor-persistent-cache")
@TestPropertySource(properties = ["waitfor.persistent.cache.mvc.test.enabled=true"])
@AutoConfigureMockMvc
class WaitForPersistentRepositoryJCacheMvcIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    @Qualifier("cacheRuntimeRepository")
    private lateinit var runtimeRepository: InMemoryAgentProcessRepository

    @Autowired
    private lateinit var agentCacheProvider: AgentCacheProvider

    private val objectMapper: ObjectMapper = EmbabelObjectMapperHolder.createDefault().get()

    @BeforeEach
    fun setUp() {
        runtimeRepository.clear()
        agentCacheProvider.getRegion(CacheRegionConfig(CacheRegions.AGENT_PROCESS_SNAPSHOTS)).clear()
    }

    @Test
    fun `complete adventure flow resumes from cache snapshot after runtime repository loss`() {
        // ── Step 1: start the adventure ───────────────────────────────────────
        val startResult = mockMvc.perform(
            MockMvcRequestBuilders.post("/cache-adventure/start")
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

        // Process is in runtime repo and snapshot is in the cache at version 1.
        assertThat(runtimeRepository.findById(awaitableDto.processId)?.status)
            .isEqualTo(AgentProcessStatusCode.WAITING)
        val snapshotStore = CacheBackedAgentProcessSnapshotStore(
            agentCacheProvider.getRegion(CacheRegionConfig(CacheRegions.AGENT_PROCESS_SNAPSHOTS))
        )
        assertThat(snapshotStore.findLatestByProcessId(awaitableDto.processId)?.version).isEqualTo(1L)

        // ── Step 2: simulate pod loss ─────────────────────────────────────────
        runtimeRepository.clear()
        assertThat(runtimeRepository.findById(awaitableDto.processId)).isNull()

        // ── Step 3: continue — PersistentAgentProcessRepository restores from cache
        mockMvc.perform(
            MockMvcRequestBuilders.post("/cache-adventure/${awaitableDto.processId}/continue")
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
    }

    // ─── Reference @Bean configuration ───────────────────────────────────────
    /**
     * Reference configuration for cache-backed agent process persistence.
     *
     * Copy these beans into your `@Configuration` class and swap the
     * [cacheManager] bean for the backend of your choice. Every other bean
     * is identical regardless of backend.
     */
    @TestConfiguration
    @Profile("waitfor-persistent-cache")
    class WaitForPersistentRepositoryCacheTestConfig {

        // ── STEP 1: choose your backend ───────────────────────────────────────
        //
        // This is the ONLY bean that changes between backends.
        //
        // Redis (production distributed cache):
        //   @Bean
        //   fun cacheManager(factory: RedisConnectionFactory): CacheManager =
        //       RedisCacheManager.builder(factory)
        //           .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
        //               .entryTtl(Duration.ofHours(1)))
        //           .build()
        //
        // Hazelcast (distributed, JCache-compliant):
        //   @Bean
        //   fun cacheManager(hz: HazelcastInstance): CacheManager =
        //       HazelcastCacheManager(hz)
        //
        // Ehcache 3 / JCache (local tiered, no infra required):
        //   @Bean
        //   fun cacheManager(): CacheManager =
        //       JCacheCacheManager(Caching.getCachingProvider().cacheManager)
        // ─────────────────────────────────────────────────────────────────────
        @Bean
        fun cacheManager(): CacheManager = ConcurrentMapCacheManager()

        // ── STEP 2: wire Embabel's cache provider over your CacheManager ──────
        @Bean
        fun agentCacheProvider(cacheManager: CacheManager): AgentCacheProvider =
            SpringCacheAgentCacheProvider(cacheManager)

        // ── STEP 3: build the snapshot store ──────────────────────────────────
        //
        // CacheRegions.AGENT_PROCESS_SNAPSHOTS is the well-known region name.
        // The region is created on first access; no up-front schema needed.
        @Bean
        fun cacheBackedSnapshotStore(agentCacheProvider: AgentCacheProvider): AgentProcessSnapshotStore =
            CacheBackedAgentProcessSnapshotStore(
                agentCacheProvider.getRegion(CacheRegionConfig(CacheRegions.AGENT_PROCESS_SNAPSHOTS))
            )

        // ── STEP 4: expose the runtime repository ─────────────────────────────
        //
        // Named so the test can inject and clear it to simulate a pod restart.
        // In production you do not need a named qualifier; the repository is
        // used only internally by PersistentAgentProcessRepository.
        @Bean
        fun cacheRuntimeRepository(): InMemoryAgentProcessRepository = InMemoryAgentProcessRepository()

        // ── STEP 5: assemble the durable repository ───────────────────────────
        //
        // AgentProcessPersistence.persistentRepository() is the stable public
        // factory. It hides the snapshot, serialisation and restore internals so
        // they can evolve without affecting your configuration.
        //
        // blackboardEntrySerializers: supply one BlackboardEntrySerializer for
        // each custom Awaitable or domain object that the Jackson fallback cannot
        // round-trip (types with no no-arg constructor, sealed classes, etc.).
        // Simple data classes are handled automatically by the Jackson fallback.
        @Bean
        @Qualifier("waitForCacheRepository")
        fun agentProcessRepository(
            @Qualifier("cacheRuntimeRepository") runtimeRepository: InMemoryAgentProcessRepository,
            @Qualifier("cacheBackedSnapshotStore") snapshotStore: AgentProcessSnapshotStore,
        ): AgentProcessRepository {
            val objectMapper = EmbabelObjectMapperHolder.createDefault().get()
            val agent = AgentMetadataReader().createAgentMetadata(AdventureAgent()) as Agent
            return AgentProcessPersistence.persistentRepository(
                runtimeRepository = runtimeRepository,
                snapshotStore = snapshotStore,
                objectMapper = objectMapper,
                agents = { listOf(agent) },
                platformServices = { dummyPlatformServices() },
                blackboardEntrySerializers = listOf(ChoiceAwaitableSerializer(objectMapper)),
            )
        }
    }
}
