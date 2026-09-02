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
package com.embabel.agent.spi.persistence

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.spi.support.persistence.AgentProcessSnapshotFactory
import com.embabel.agent.spi.support.persistence.AgentProcessSnapshotRestorer
import com.embabel.agent.spi.support.persistence.BlackboardEntrySerializerResolver
import com.embabel.agent.spi.support.persistence.InMemoryBlackboardSnapshotter
import com.embabel.agent.spi.support.persistence.JacksonAgentProcessStateSerializer
import com.embabel.agent.spi.support.persistence.JacksonBlackboardEntrySerializer
import com.embabel.agent.spi.support.persistence.LifecycleCheckpointPolicy
import com.embabel.agent.spi.support.persistence.PersistentAgentProcessRepository
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * Entry point for assembling a durable [AgentProcessRepository].
 *
 * An application supplies an [AgentProcessSnapshotStore] for its own backend, and
 * this builds the snapshot, serialization and restore machinery around it. That
 * machinery stays internal deliberately: the snapshot model is an implementation
 * detail that must be free to change, whereas the pieces an integrator supplies
 * ([AgentProcessSnapshotStore], [AgentProcessCheckpointPolicy] and
 * [BlackboardEntrySerializer]) are stable contracts.
 *
 * ```
 * val repository = AgentProcessPersistence.persistentRepository(
 *     runtimeRepository = InMemoryAgentProcessRepository(),
 *     snapshotStore = myRedisSnapshotStore,
 *     objectMapper = objectMapper,
 *     agents = agentPlatform::agents,
 *     platformServices = { agentPlatform.platformServices },
 * )
 * ```
 */
object AgentProcessPersistence {

    /**
     * Decorate [runtimeRepository] so that processes are checkpointed to
     * [snapshotStore] and restored from it when runtime state has been lost.
     *
     * The returned repository reads through: [AgentProcessRepository.findById]
     * falls back to the snapshot store on a miss and warms the runtime
     * repository with what it restores.
     *
     * @param runtimeRepository fast, usually in-memory, repository holding live processes
     * @param snapshotStore durable backend supplied by the application
     * @param objectMapper mapper used for the snapshot payload and for blackboard
     * values not claimed by a supplied serializer
     * @param agents supplier of currently registered agents. Snapshots store the
     * agent name, so restore binds a process to the live definition; this is a
     * supplier because the platform is constructed after the repository
     * @param platformServices supplier of platform services for restored processes,
     * a supplier for the same reason
     * @param checkpointPolicy when a process is written to the snapshot store.
     * Defaults to [LifecycleCheckpointPolicy], which checkpoints processes that
     * are `WAITING` or finished
     * @param blackboardEntrySerializers application serializers for blackboard
     * values needing special handling, consulted in Spring `@Order` before the
     * Jackson fallback
     * @param clock clock used to stamp snapshots
     */
    fun persistentRepository(
        runtimeRepository: AgentProcessRepository,
        snapshotStore: AgentProcessSnapshotStore,
        objectMapper: ObjectMapper,
        agents: () -> Collection<Agent>,
        platformServices: () -> PlatformServices,
        checkpointPolicy: AgentProcessCheckpointPolicy = LifecycleCheckpointPolicy,
        blackboardEntrySerializers: List<BlackboardEntrySerializer> = emptyList(),
        clock: Clock = Clock.systemUTC(),
    ): AgentProcessRepository {
        val blackboardSnapshotter = InMemoryBlackboardSnapshotter(
            BlackboardEntrySerializerResolver(
                serializers = blackboardEntrySerializers,
                fallback = JacksonBlackboardEntrySerializer(objectMapper),
            )
        )
        return PersistentAgentProcessRepository(
            runtimeRepository = runtimeRepository,
            snapshotStore = snapshotStore,
            checkpointPolicy = checkpointPolicy,
            snapshotFactory = AgentProcessSnapshotFactory(blackboardSnapshotter),
            snapshotSerializer = JacksonAgentProcessStateSerializer(objectMapper, clock),
            snapshotRestorer = AgentProcessSnapshotRestorer(blackboardSnapshotter),
            agents = agents,
            platformServices = platformServices,
        )
    }
}
