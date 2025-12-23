/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spec

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.spec.model.GoalDefinition
import com.embabel.agent.spec.model.PromptedActionDefinition
import com.embabel.agent.spec.persistence.StepDefinitionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StepDefinitionAgentScopeBuilderTest {

    private lateinit var agentPlatform: AgentPlatform
    private lateinit var repository: StepDefinitionRepository
    private lateinit var dataDictionary: DataDictionary

    @BeforeEach
    fun setUp() {
        agentPlatform = mockk(relaxed = true)
        repository = mockk()
        dataDictionary = mockk()
        every { dataDictionary.domainTypes } returns emptyList()
    }

    @Nested
    inner class Build {

        @Test
        fun `build returns empty scope when repository is empty`() {
            every { repository.findAll() } returns emptyList()

            val deployer = StepDefinitionAgentScopeBuilder(
                name = "test-scope",
                agentPlatform = agentPlatform,
                repository = repository,
                dataDictionary = dataDictionary,
            )

            val scope = deployer.createAgentScope()

            assertEquals("test-scope", scope.name)
            assertTrue(scope.actions.isEmpty())
            assertTrue(scope.goals.isEmpty())
        }

        @Test
        fun `build converts action definitions to actions`() {
            val actionDef = PromptedActionDefinition(
                name = "summarize",
                description = "Summarize text",
                inputTypeNames = setOf("UserInput"),
                outputTypeName = "Summary",
                prompt = "Summarize: {{userInput}}",
            )
            every { repository.findAll() } returns listOf(actionDef)

            val deployer = StepDefinitionAgentScopeBuilder(
                name = "test-scope",
                agentPlatform = agentPlatform,
                repository = repository,
                dataDictionary = dataDictionary,
            )

            val scope = deployer.createAgentScope()

            assertEquals(1, scope.actions.size)
            val action = scope.actions.first()
            assertEquals("summarize", action.name)
            assertEquals("Summarize text", action.description)
        }

        @Test
        fun `build converts goal definitions to goals`() {
            val goalDef = GoalDefinition(
                name = "generateReport",
                description = "Generate a report",
                outputTypeName = "Report",
            )
            every { repository.findAll() } returns listOf(goalDef)

            val deployer = StepDefinitionAgentScopeBuilder(
                name = "test-scope",
                agentPlatform = agentPlatform,
                repository = repository,
                dataDictionary = dataDictionary,
            )

            val scope = deployer.createAgentScope()

            assertEquals(1, scope.goals.size)
            val goal = scope.goals.first()
            assertEquals("generateReport", goal.name)
            assertEquals("Generate a report", goal.description)
        }

        @Test
        fun `build handles mixed actions and goals`() {
            val actionDef = PromptedActionDefinition(
                name = "analyze",
                description = "Analyze data",
                inputTypeNames = setOf("Data"),
                outputTypeName = "Analysis",
                prompt = "Analyze: {{data}}",
            )
            val goalDef = GoalDefinition(
                name = "completeAnalysis",
                description = "Complete the analysis",
                outputTypeName = "Analysis",
            )
            every { repository.findAll() } returns listOf(actionDef, goalDef)

            val deployer = StepDefinitionAgentScopeBuilder(
                name = "mixed-scope",
                agentPlatform = agentPlatform,
                repository = repository,
                dataDictionary = dataDictionary,
            )

            val scope = deployer.createAgentScope()

            assertEquals(1, scope.actions.size)
            assertEquals(1, scope.goals.size)
            assertEquals("analyze", scope.actions.first().name)
            assertEquals("completeAnalysis", scope.goals.first().name)
        }

        @Test
        fun `build preserves action input and output bindings`() {
            val actionDef = PromptedActionDefinition(
                name = "transform",
                description = "Transform input to output",
                inputTypeNames = setOf("InputA", "InputB"),
                outputTypeName = "OutputC",
                prompt = "Transform inputs",
            )
            every { repository.findAll() } returns listOf(actionDef)

            val deployer = StepDefinitionAgentScopeBuilder(
                name = "test-scope",
                agentPlatform = agentPlatform,
                repository = repository,
                dataDictionary = dataDictionary,
            )

            val scope = deployer.createAgentScope()

            val action = scope.actions.first()
            assertEquals(2, action.inputs.size)
            assertTrue(action.inputs.any { it.name == "inputA" && it.type == "InputA" })
            assertTrue(action.inputs.any { it.name == "inputB" && it.type == "InputB" })
            assertEquals(1, action.outputs.size)
            assertTrue(action.outputs.any { it.name == "outputC" && it.type == "OutputC" })
        }
    }

    @Nested
    inner class Deploy {

        @Test
        fun `deploy calls platform deploy with built scope`() {
            every { repository.findAll() } returns emptyList()
            every { agentPlatform.deploy(any<AgentScope>()) } returns agentPlatform

            val deployer = StepDefinitionAgentScopeBuilder(
                name = "deploy-test",
                agentPlatform = agentPlatform,
                repository = repository,
                dataDictionary = dataDictionary,
            )

            val scope = deployer.deploy()

            assertEquals("deploy-test", scope.name)
            verify { agentPlatform.deploy(any<AgentScope>()) }
        }
    }
}
