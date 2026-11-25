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
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.hitl.AwaitableResponseException
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.core.hitl.FormResponse
import com.embabel.agent.core.hitl.ValidationError
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.DefaultAgentPlatform
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.config.spring.AgentPlatformConfiguration
import com.embabel.agent.spi.config.spring.AsyncConfiguration
import com.embabel.agent.spi.config.spring.ContextRepositoryProperties
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.support.RankingProperties
import com.embabel.agent.spi.support.persistence.FormBindingRequestSerializer
import com.embabel.agent.test.integration.ScriptedLlmOperations
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.embabel.ux.form.Form
import com.embabel.ux.form.FormSubmission
import com.embabel.ux.form.TextField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.sql.DriverManager
import java.util.UUID

class FormBindingRequestJdbcIntegrationTest {

    @Test
    fun `form serialization is not installed by the default repository`() {
        val url = databaseUrl()
        DriverManager.getConnection(url, "sa", "").use {
            startApplication(url).use { application ->
                val waiting = runUntilWaiting(application)
                val snapshot = requireNotNull(application.snapshotStore().findLatestByProcessId(waiting.id))
                assertThat(snapshot.payload.toString(Charsets.UTF_8))
                    .contains(FormBindingRequest::class.java.name)
                    .doesNotContain("embabel.form-binding-request.v1")
            }
        }
    }

    @Test
    fun `restores a waiting form after restart and resumes with its original request id`() {
        val url = databaseUrl()
        // Keep the H2 database alive while the first Spring Boot context is closed.
        DriverManager.getConnection(url, "sa", "").use {
            val waiting = startApplication(url, FormSerializerConfiguration::class.java).use { application ->
                runUntilWaiting(application).also { process ->
                    val snapshot = requireNotNull(application.snapshotStore().findLatestByProcessId(process.id))
                    assertThat(snapshot.version).isEqualTo(1L)
                    assertThat(snapshot.payload.toString(Charsets.UTF_8)).contains("embabel.form-binding-request.v1")
                }
            }
            val original = requireNotNull(waiting.blackboard.last(FormBindingRequest::class.java))

            startApplication(url, FormSerializerConfiguration::class.java).use { application ->
                val repository = application.getBean(AgentProcessRepository::class.java)
                val restored = requireNotNull(repository.findById(waiting.id))
                val request = requireNotNull(restored.blackboard.last(FormBindingRequest::class.java))
                assertThat(restored).isNotSameAs(waiting)
                assertThat(restored.status).isEqualTo(AgentProcessStatusCode.WAITING)
                assertThat(request.id).isEqualTo(original.id)
                assertThat(request.timestamp).isEqualTo(original.timestamp)
                assertThat(request.payload).isEqualTo(original.payload)
                assertThat(request.population).isEqualTo(FormApproval("pending"))
                assertThat(request.validationErrors)
                    .containsExactly(FormApprovalValidationError("Previous decision was incomplete"))
                assertThat(request.persistent()).isTrue()
                assertThat(request.bindingName).isEqualTo("approval")

                request.onResponse(
                    FormResponse(
                        awaitableId = original.id,
                        formSubmission = FormSubmission(formId = original.payload.id, values = mapOf("decision" to "approved")),
                    ),
                    restored,
                )
                val completed = restored.run()
                assertThat(completed.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
                assertThat(completed.blackboard.last(FormApprovalResult::class.java))
                    .isEqualTo(FormApprovalResult("approved"))
                val snapshot = requireNotNull(application.snapshotStore().findLatestByProcessId(waiting.id))
                assertThat(snapshot.version).isEqualTo(2L)
                assertThat(snapshot.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
            }
        }
    }

    private fun runUntilWaiting(application: ConfigurableApplicationContext): AgentProcess {
        val platform = application.getBean(AgentPlatform::class.java)
        val agent = platform.agents().single()
        return platform.runAgentFrom(agent, bindings = mapOf("input" to UserInput("pending"))).also {
            assertThat(it.status).isEqualTo(AgentProcessStatusCode.WAITING)
        }
    }

    private fun ConfigurableApplicationContext.snapshotStore(): AgentProcessSnapshotStore =
        getBean(AgentProcessSnapshotStore::class.java)

    private fun databaseUrl() = "jdbc:h2:mem:form-approval-${UUID.randomUUID()};MODE=PostgreSQL"

    private fun startApplication(url: String, vararg configuration: Class<*>): ConfigurableApplicationContext =
        SpringApplicationBuilder(FormPersistenceApplication::class.java, *configuration)
            .web(WebApplicationType.NONE)
            .profiles("form-jdbc")
            .run(
                "--embabel.agent.platform.persistence.enabled=true",
                "--form-test.jdbc-url=$url",
                // This agent makes no model calls, so an unused model provider needs no configuration.
                "--spring.main.lazy-initialization=true",
            )
            .also { it.getBean(AgentPlatform::class.java).deploy(it.getBean(CoreAgent::class.java)) }

    @Configuration(proxyBeanMethods = false)
    @Profile("form-jdbc")
    @Import(AgentPlatformConfiguration::class, DefaultAgentPlatform::class, AsyncConfiguration::class)
    @EnableConfigurationProperties(ContextRepositoryProperties::class, RankingProperties::class)
    class FormPersistenceApplication {

        @Bean
        fun snapshotStore(@Value("\${form-test.jdbc-url}") url: String): AgentProcessSnapshotStore {
            val dataSource = DriverManagerDataSource(url, "sa", "")
            ResourceDatabasePopulator(ClassPathResource("com/embabel/agent/jdbc/schema.sql")).execute(dataSource)
            return JdbcAgentProcessSnapshotStore(JdbcTemplate(dataSource))
        }

        @Bean
        fun llmOperations(): LlmOperations = ScriptedLlmOperations()

        @Bean
        fun approvalAgent(): CoreAgent =
            AgentMetadataReader().createAgentMetadata(FormApprovalAgent()) as CoreAgent
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("form-jdbc")
    class FormSerializerConfiguration {

        @Bean
        fun formBindingRequestSerializer(mapper: EmbabelObjectMapperHolder) =
            FormBindingRequestSerializer(mapper.get())
    }
}

internal data class FormApproval(val decision: String)

private data class FormApprovalValidationError(val message: String) : ValidationError

private data class FormApprovalResult(val decision: String)

@Agent(description = "JDBC form approval agent")
private class FormApprovalAgent {

    @Action
    fun requestApproval(input: UserInput): FormApproval = throw AwaitableResponseException(
        FormBindingRequest(
            form = Form("Approval", listOf(TextField(id = "decision", label = "Decision"))),
            outputClass = FormApproval::class.java,
            population = FormApproval(input.content),
            validationErrors = listOf(FormApprovalValidationError("Previous decision was incomplete")),
            persistent = true,
            bindingName = "approval",
        ),
    )

    @Action
    @AchievesGoal(description = "Finish the form approval")
    fun finish(approval: FormApproval) = FormApprovalResult(approval.decision)
}
