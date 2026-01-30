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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.core.AgentPlatform
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.embabel.agent.core.Agent as AgentCore

/**
 * Tests for [Subagent] tool.
 */
class SubagentTest {

    // Test input/output types
    data class TestInput(val message: String)
    data class TestOutput(val result: String)

    // Test agent class
    @Agent(description = "Test agent for subagent tests")
    class TestAgent {
        @Action
        fun processInput(input: TestInput): TestOutput {
            return TestOutput("Processed: ${input.message}")
        }

        @AchievesGoal(description = "Test goal achieved")
        @Action
        fun complete(output: TestOutput): TestOutput = output
    }

    // Agent without @Agent annotation
    class NotAnAgent {
        fun doSomething(): String = "done"
    }

    @Nested
    inner class BuilderTest {

        @Test
        fun `ofClass returns builder that creates subagent with consuming`() {
            val builder = Subagent.ofClass(TestAgent::class.java)
            val subagent = builder.consuming(TestInput::class.java)

            assertNotNull(subagent)
            assertTrue(subagent is Tool)
            assertTrue(subagent.toString().contains("TestAgent"))
        }

        @Test
        fun `ofClass with KClass returns builder`() {
            val subagent = Subagent.ofClass(TestAgent::class).consuming(TestInput::class)

            assertNotNull(subagent)
            assertTrue(subagent.toString().contains("TestAgent"))
        }

        @Test
        fun `ofClass reified returns builder`() {
            val builder = Subagent.ofClass<TestAgent>()
            val subagent = builder.consuming<TestInput>()

            assertNotNull(subagent)
            assertTrue(subagent.toString().contains("TestAgent"))
        }

        @Test
        fun `ofClass throws for non-agent class`() {
            val exception = assertThrows<IllegalArgumentException> {
                Subagent.ofClass(NotAnAgent::class.java)
            }
            assertTrue(exception.message?.contains("must be annotated with @Agent") == true)
        }

        @Test
        fun `byName returns builder that creates subagent`() {
            val subagent = Subagent.byName("MyAgent").consuming(TestInput::class.java)

            assertNotNull(subagent)
            assertEquals("Subagent(name=MyAgent)", subagent.toString())
        }

        @Test
        fun `byName throws for blank name`() {
            assertThrows<IllegalArgumentException> {
                Subagent.byName("")
            }
            assertThrows<IllegalArgumentException> {
                Subagent.byName("   ")
            }
        }

        @Test
        fun `ofInstance returns builder that creates subagent`() {
            val mockAgent = mockk<AgentCore>()
            every { mockAgent.name } returns "MockAgent"

            val subagent = Subagent.ofInstance(mockAgent).consuming(TestInput::class.java)

            assertNotNull(subagent)
            assertEquals("Subagent(agent=MockAgent)", subagent.toString())
        }

        @Test
        fun `ofAnnotatedInstance returns builder that creates subagent`() {
            val instance = TestAgent()

            val subagent = Subagent.ofAnnotatedInstance(instance).consuming(TestInput::class.java)

            assertNotNull(subagent)
            assertTrue(subagent.toString().contains("TestAgent"))
        }

        @Test
        fun `ofAnnotatedInstance throws for non-agent instance`() {
            val instance = NotAnAgent()

            val exception = assertThrows<IllegalArgumentException> {
                Subagent.ofAnnotatedInstance(instance)
            }
            assertTrue(exception.message?.contains("must be annotated with @Agent") == true)
        }
    }

    @Nested
    inner class ResolveAgentTest {

        @Test
        fun `resolveAgent with FromAgent returns agent directly`() {
            val mockAgent = mockk<AgentCore>()
            every { mockAgent.name } returns "DirectAgent"
            every { mockAgent.description } returns "Direct agent"

            val subagent = Subagent.ofInstance(mockAgent).consuming(TestInput::class.java)
            val mockPlatform = mockk<AgentPlatform>()

            val resolved = subagent.resolveAgent(mockPlatform)

            assertEquals(mockAgent, resolved)
        }

        @Test
        fun `resolveAgent with FromName finds agent in platform`() {
            val mockAgent = mockk<AgentCore>()
            every { mockAgent.name } returns "NamedAgent"

            val mockPlatform = mockk<AgentPlatform>()
            every { mockPlatform.agents() } returns listOf(mockAgent)

            val subagent = Subagent.byName("NamedAgent").consuming(TestInput::class.java)
            val resolved = subagent.resolveAgent(mockPlatform)

            assertEquals(mockAgent, resolved)
        }

        @Test
        fun `resolveAgent with FromName throws when agent not found`() {
            val mockPlatform = mockk<AgentPlatform>()
            every { mockPlatform.name } returns "TestPlatform"
            every { mockPlatform.agents() } returns emptyList()

            val subagent = Subagent.byName("NonExistentAgent").consuming(TestInput::class.java)

            val exception = assertThrows<IllegalArgumentException> {
                subagent.resolveAgent(mockPlatform)
            }
            assertTrue(exception.message?.contains("not found in platform") == true)
        }

        @Test
        fun `resolveAgent with FromClass finds agent by derived name`() {
            val mockAgent = mockk<AgentCore>()
            every { mockAgent.name } returns "TestAgent"

            val mockPlatform = mockk<AgentPlatform>()
            every { mockPlatform.agents() } returns listOf(mockAgent)

            val subagent = Subagent.ofClass(TestAgent::class.java).consuming(TestInput::class.java)
            val resolved = subagent.resolveAgent(mockPlatform)

            assertEquals(mockAgent, resolved)
        }

        @Test
        fun `resolveAgent with FromAnnotatedInstance finds agent by derived name`() {
            val mockAgent = mockk<AgentCore>()
            every { mockAgent.name } returns "TestAgent"

            val mockPlatform = mockk<AgentPlatform>()
            every { mockPlatform.agents() } returns listOf(mockAgent)

            val instance = TestAgent()
            val subagent = Subagent.ofAnnotatedInstance(instance).consuming(TestInput::class.java)
            val resolved = subagent.resolveAgent(mockPlatform)

            assertEquals(mockAgent, resolved)
        }
    }

    @Nested
    inner class ToolInterfaceTest {

        @Test
        fun `subagent implements Tool interface`() {
            val subagent = Subagent.ofClass(TestAgent::class.java).consuming(TestInput::class.java)

            assertTrue(subagent is Tool)
        }

        @Test
        fun `metadata returns default`() {
            val subagent = Subagent.ofClass(TestAgent::class.java).consuming(TestInput::class.java)

            assertEquals(Tool.Metadata.DEFAULT, subagent.metadata)
        }
    }

    @Nested
    inner class DefinitionTest {

        @Test
        fun `definition can be accessed without AgentProcess context for FromClass`() {
            val subagent = Subagent.ofClass(TestAgent::class.java).consuming(TestInput::class.java)

            // This should NOT throw - definition should be resolvable without AgentProcess
            val definition = subagent.definition

            assertEquals("TestAgent", definition.name)
            assertEquals("Test agent for subagent tests", definition.description)
            assertNotNull(definition.inputSchema)
        }

        @Test
        fun `definition can be accessed without AgentProcess context for FromAnnotatedInstance`() {
            val instance = TestAgent()
            val subagent = Subagent.ofAnnotatedInstance(instance).consuming(TestInput::class.java)

            // This should NOT throw - definition should be resolvable without AgentProcess
            val definition = subagent.definition

            assertEquals("TestAgent", definition.name)
            assertEquals("Test agent for subagent tests", definition.description)
            assertNotNull(definition.inputSchema)
        }

        @Test
        fun `definition can be accessed without AgentProcess context for FromName`() {
            val subagent = Subagent.byName("SomeAgent").consuming(TestInput::class.java)

            // This should NOT throw - definition should be resolvable without AgentProcess
            val definition = subagent.definition

            assertEquals("SomeAgent", definition.name)
            assertTrue(definition.description.contains("SomeAgent"))
        }

        @Test
        fun `definition can be accessed without AgentProcess context for FromAgent`() {
            val mockAgent = mockk<AgentCore>()
            every { mockAgent.name } returns "MockAgent"
            every { mockAgent.description } returns "Mock agent description"

            val subagent = Subagent.ofInstance(mockAgent).consuming(TestInput::class.java)

            // This should NOT throw - we have the input class explicitly
            val definition = subagent.definition

            assertEquals("MockAgent", definition.name)
            assertEquals("Mock agent description", definition.description)
        }

        @Test
        fun `definition includes correct input schema from explicit input class`() {
            val subagent = Subagent.ofClass(TestAgent::class.java).consuming(TestInput::class.java)

            val definition = subagent.definition
            val schemaJson = definition.inputSchema.toJsonSchema()

            // The schema should contain the TestInput structure
            assertTrue(schemaJson.isNotBlank())
            assertTrue(schemaJson.contains("message"))
        }
    }

    @Nested
    inner class ToStringTest {

        @Test
        fun `toString for FromAgent shows agent name`() {
            val mockAgent = mockk<AgentCore>()
            every { mockAgent.name } returns "TestAgent"

            val subagent = Subagent.ofInstance(mockAgent).consuming(TestInput::class.java)

            assertEquals("Subagent(agent=TestAgent)", subagent.toString())
        }

        @Test
        fun `toString for FromName shows name`() {
            val subagent = Subagent.byName("MyAgent").consuming(TestInput::class.java)

            assertEquals("Subagent(name=MyAgent)", subagent.toString())
        }

        @Test
        fun `toString for FromClass shows class simple name`() {
            val subagent = Subagent.ofClass(TestAgent::class.java).consuming(TestInput::class.java)

            assertEquals("Subagent(class=TestAgent)", subagent.toString())
        }

        @Test
        fun `toString for FromAnnotatedInstance shows instance class simple name`() {
            val subagent = Subagent.ofAnnotatedInstance(TestAgent()).consuming(TestInput::class.java)

            assertEquals("Subagent(instance=TestAgent)", subagent.toString())
        }
    }
}
