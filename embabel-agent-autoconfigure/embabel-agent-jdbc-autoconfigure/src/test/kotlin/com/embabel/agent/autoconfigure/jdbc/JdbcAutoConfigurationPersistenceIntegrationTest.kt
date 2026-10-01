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
package com.embabel.agent.autoconfigure.jdbc

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.waitFor
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.config.spring.AgentPlatformConfiguration
import com.embabel.agent.spi.config.spring.AgentProcessPersistenceProperties
import com.embabel.agent.spi.config.spring.ProcessRepositoryProperties
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.function.Supplier
import javax.sql.DataSource

/**
 * End-to-end cover for JDBC auto-configuration: a DataSource plus one property is
 * all an application declares, and agent processes become durable.
 *
 * Each application context stands in for a pod. Two contexts sharing one H2
 * in-memory database model two pods reaching the same backend; a context with its
 * own database is the control, proving restore travels through the store.
 *
 * The platform's repository is built with the production `agentProcessRepository`
 * wiring method over the snapshot store the auto-configuration registered.
 */
class JdbcAutoConfigurationPersistenceIntegrationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JdbcTemplateAutoConfiguration::class.java,
                JdbcSnapshotStoreAutoConfiguration::class.java,
            )
        )

    private val durable = runner.withPropertyValues(
        "embabel.agent.platform.persistence.provider=jdbc",
    )

    @Test
    fun `a process parked on one pod resumes on another`() {
        val sharedDb = sharedDataSource("jdbc-persist-shared")
        val original = waitingProcess("p1")

        durable.withDataSource(sharedDb).run { podA ->
            assertThat(podA).hasNotFailed()
            initSchema(podA)
            platformRepository(podA).save(original)
        }

        durable.withDataSource(sharedDb).run { podB ->
            val restored = platformRepository(podB).findById("p1")
            assertThat(restored).isNotNull()
            assertThat(restored!!.status).isEqualTo(AgentProcessStatusCode.WAITING)
            assertThat(restored.history).isEqualTo(original.history)
        }
    }

    @Test
    fun `a pod with a different database cannot resume the process`() {
        durable.withDataSource(sharedDataSource("jdbc-pod-a")).run { podA ->
            initSchema(podA)
            platformRepository(podA).save(waitingProcess("p1"))
        }

        durable.withDataSource(sharedDataSource("jdbc-pod-b")).run { isolated ->
            initSchema(isolated)
            assertThat(platformRepository(isolated).findById("p1")).isNull()
        }
    }

    @Test
    fun `without the provider property no snapshot store is registered`() {
        runner.withDataSource(sharedDataSource("jdbc-no-provider")).run { ctx ->
            assertThat(ctx).doesNotHaveBean(AgentProcessSnapshotStore::class.java)
        }
    }

    @Test
    fun `findByParentId restores persisted children from snapshot store on another pod`() {
        val sharedDb = sharedDataSource("jdbc-parent-child")
        val parent = waitingProcess("parent-1")
        val child = waitingProcess("child-1", parentId = "parent-1")

        durable.withDataSource(sharedDb).run { podA ->
            initSchema(podA)
            val repo = platformRepository(podA)
            repo.save(parent)
            repo.save(child)
        }

        durable.withDataSource(sharedDb).run { podB ->
            val children = platformRepository(podB).findByParentId("parent-1")
            assertThat(children).hasSize(1)
            assertThat(children.first().id).isEqualTo("child-1")
            assertThat(children.first().status).isEqualTo(AgentProcessStatusCode.WAITING)
        }
    }

    @Test
    fun `findByParentId returns empty list when no children exist in store`() {
        durable.withDataSource(sharedDataSource("jdbc-no-children")).run { ctx ->
            initSchema(ctx)
            assertThat(platformRepository(ctx).findByParentId("nonexistent-parent")).isEmpty()
        }
    }

    @Test
    fun `provider=jdbc without a DataSource fails with a clear error`() {
        durable.run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure)
                .hasMessageContaining("JdbcTemplate")
                .hasMessageContaining(AgentProcessPersistenceProperties.PREFIX + ".provider=jdbc")
        }
    }

    private fun initSchema(ctx: AssertableApplicationContext) {
        ctx.getBean(JdbcTemplate::class.java).execute(
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

    private fun sharedDataSource(dbName: String): DataSource =
        DriverManagerDataSource(
            "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "sa",
            "",
        )

    private fun ApplicationContextRunner.withDataSource(dataSource: DataSource): ApplicationContextRunner =
        withBean(DataSource::class.java, Supplier { dataSource })

    private fun platformRepository(context: AssertableApplicationContext): AgentProcessRepository =
        AgentPlatformConfiguration().agentProcessRepository(
            processRepositoryProperties = ProcessRepositoryProperties(),
            persistenceProperties = AgentProcessPersistenceProperties(),
            snapshotStore = context.getBeanProvider(AgentProcessSnapshotStore::class.java),
            blackboardEntrySerializers = context.getBeanProvider(BlackboardEntrySerializer::class.java),
            embabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            agentPlatform = agentPlatform(),
        )

    private fun agentPlatform(): ObjectProvider<AgentPlatform> {
        val platform = mockk<AgentPlatform>(relaxed = true)
        every { platform.agents() } returns listOf(ConfirmingAgent)
        every { platform.platformServices } returns dummyPlatformServices()
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton("agentPlatform", platform)
        return beanFactory.getBeanProvider(AgentPlatform::class.java)
    }

    private fun waitingProcess(id: String, parentId: String? = null): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return SimpleAgentProcess(
            id = id,
            parentId = parentId,
            agent = ConfirmingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        ).also {
            assertThat(it.run().status).isEqualTo(AgentProcessStatusCode.WAITING)
        }
    }

    data class Suspect(val name: String)
    data class Verdict(val name: String)

    companion object {
        private val ConfirmingAgent: Agent =
            agent("JdbcAutoConfigurationWaiter", description = "Waits for confirmation") {
                transformation<UserInput, Suspect>(name = "await-confirmation") {
                    waitFor(ConfirmationRequest(Suspect(name = "Rod"), "Is this the dude?"))
                }
                transformation<Suspect, Verdict>(name = "decide") {
                    Verdict(name = it.input.name)
                }
                goal(name = "done", description = "done", satisfiedBy = Verdict::class)
            }
    }
}
