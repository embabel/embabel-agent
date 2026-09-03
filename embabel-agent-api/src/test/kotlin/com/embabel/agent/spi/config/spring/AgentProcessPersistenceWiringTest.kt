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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.agent.core.support.DslWaitingAgent
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.persistence.InMemoryAgentProcessSnapshotStore
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.http.MediaType

/**
 * Wiring behaviour of the `agentProcessRepository` bean.
 *
 * Calls the `@Bean` method directly rather than booting a context, because the
 * decision under test is which repository gets built. Assertions are behavioural
 * (did a snapshot land in the store) rather than type checks, since the
 * persistent decorator is deliberately internal.
 */
class AgentProcessPersistenceWiringTest {

    private val configuration = AgentPlatformConfiguration()
    private val objectMapperHolder = EmbabelObjectMapperHolder.createDefault()

    @Test
    fun `plain in memory repository when no snapshot store is available`() {
        val repository = configuration.agentProcessRepository(
            processRepositoryProperties = ProcessRepositoryProperties(),
            persistenceProperties = AgentProcessPersistenceProperties(),
            snapshotStore = emptyProvider(),
            blackboardEntrySerializers = emptyProvider(),
            embabelObjectMapperHolder = objectMapperHolder,
            agentPlatform = explodingProvider(),
        )

        // Nothing to assert against a store, so assert the process simply round
        // trips through the runtime repository.
        val process = waitingProcess("p1")
        repository.save(process)
        assertNotNull(repository.findById("p1"))
    }

    @Test
    fun `persists when a snapshot store is available`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val repository = repository(store)

        repository.save(waitingProcess("p1"))

        val snapshot = store.findLatestByProcessId("p1")
        assertNotNull(snapshot)
        assertEquals(AgentProcessStatusCode.WAITING, snapshot?.status)
    }

    @Test
    fun `does not persist when disabled despite an available store`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val repository = repository(
            store,
            persistenceProperties = AgentProcessPersistenceProperties(enabled = false),
        )

        repository.save(waitingProcess("p1"))

        assertNull(store.findLatestByProcessId("p1"))
        assertNotNull(repository.findById("p1"), "the runtime repository must still work")
    }

    @Test
    fun `honours the waiting checkpoint policy`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val repository = repository(
            store,
            persistenceProperties = AgentProcessPersistenceProperties(
                checkpointPolicy = AgentProcessPersistenceProperties.CheckpointPolicy.WAITING,
            ),
        )

        // NOT_STARTED is neither WAITING nor finished, so the wait policy skips it.
        repository.save(newProcess("p1"))

        assertNull(store.findLatestByProcessId("p1"))
    }

    @Test
    fun `applies registered blackboard entry serializer beans`() {
        val serializer = RecordingSerializer()
        val repository = repository(
            InMemoryAgentProcessSnapshotStore(),
            serializers = providerOf(BlackboardEntrySerializer::class.java, serializer),
        )

        repository.save(waitingProcess("p1"))

        assertTrue(serializer.used, "serializer beans should reach the persistence layer")
    }

    @Test
    fun `does not resolve the agent platform eagerly`() {
        // The platform depends on this repository, so resolving it while building
        // the bean would be a circular dependency. Saving must not touch it.
        val store = InMemoryAgentProcessSnapshotStore()
        val repository = configuration.agentProcessRepository(
            processRepositoryProperties = ProcessRepositoryProperties(),
            persistenceProperties = AgentProcessPersistenceProperties(),
            snapshotStore = providerOf(AgentProcessSnapshotStore::class.java, store),
            blackboardEntrySerializers = emptyProvider(),
            embabelObjectMapperHolder = objectMapperHolder,
            agentPlatform = explodingProvider(),
        )

        repository.save(waitingProcess("p1"))

        assertNotNull(store.findLatestByProcessId("p1"))
    }

    private fun repository(
        store: AgentProcessSnapshotStore,
        persistenceProperties: AgentProcessPersistenceProperties = AgentProcessPersistenceProperties(),
        serializers: ObjectProvider<BlackboardEntrySerializer> = emptyProvider(),
    ) = configuration.agentProcessRepository(
        processRepositoryProperties = ProcessRepositoryProperties(),
        persistenceProperties = persistenceProperties,
        snapshotStore = providerOf(AgentProcessSnapshotStore::class.java, store),
        blackboardEntrySerializers = serializers,
        embabelObjectMapperHolder = objectMapperHolder,
        agentPlatform = explodingProvider(),
    )

    /**
     * Real [ObjectProvider] over the given singletons, so the test exercises
     * Spring's own resolution rather than a hand-rolled stub.
     */
    private fun <T : Any> providerOf(
        type: Class<T>,
        vararg beans: T,
    ): ObjectProvider<T> {
        val beanFactory = DefaultListableBeanFactory()
        beans.forEachIndexed { index, bean -> beanFactory.registerSingleton("bean$index", bean) }
        return beanFactory.getBeanProvider(type)
    }

    private inline fun <reified T : Any> emptyProvider(): ObjectProvider<T> =
        DefaultListableBeanFactory().getBeanProvider(T::class.java)

    /**
     * Provider that fails if resolved, proving the agent platform is only touched
     * lazily during restore.
     */
    private fun explodingProvider(): ObjectProvider<AgentPlatform> =
        object : ObjectProvider<AgentPlatform> {
            override fun getObject(): AgentPlatform =
                throw AssertionError("agent platform must not be resolved here")

            override fun getObject(vararg args: Any?): AgentPlatform = getObject()

            override fun getIfAvailable(): AgentPlatform = getObject()

            override fun getIfUnique(): AgentPlatform = getObject()
        }

    private fun waitingProcess(id: String) =
        newProcess(id).also {
            assertEquals(AgentProcessStatusCode.WAITING, it.run().status)
        }

    private fun newProcess(id: String): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return SimpleAgentProcess(
            id = id,
            parentId = null,
            agent = DslWaitingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        )
    }

    private class RecordingSerializer : BlackboardEntrySerializer {

        var used: Boolean = false

        override fun supportsSerialization(value: Any): Boolean = value is UserInput

        override fun serialize(
            value: Any,
            context: BlackboardEntrySerializationContext,
        ): SerializedBlackboardValue {
            used = true
            return SerializedBlackboardValue(
                typeName = value.javaClass.name,
                contentType = MediaType.TEXT_PLAIN_VALUE,
                payload = value.toString().toByteArray(),
            )
        }

        override fun deserialize(
            value: SerializedBlackboardValue,
            context: BlackboardEntryDeserializationContext,
        ): Any = value.payload.toString(Charsets.UTF_8)
    }
}
