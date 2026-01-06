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
package com.embabel.agent.spec.model

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.ToolGroupDescription
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StepSpecEmitTest {

    private lateinit var stepContext: StepSpecContext
    private lateinit var dataDictionary: DataDictionary

    @BeforeEach
    fun setUp() {
        dataDictionary = mockk()
        every { dataDictionary.domainTypes } returns emptyList()
        stepContext = StepSpecContext(
            name = "test-context",
            dataDictionary = dataDictionary,
            toolGroups = emptyList(),
        )
    }

    @Nested
    inner class GoalSpecEmit {

        @Test
        fun `emit creates Goal with correct name and description`() {
            val goalSpec = GoalSpec(
                name = "completeTask",
                description = "Complete the task successfully",
                outputTypeName = "TaskResult",
            )

            val goal = goalSpec.emit(stepContext)

            assertEquals("completeTask", goal.name)
            assertEquals("Complete the task successfully", goal.description)
        }

        @Test
        fun `emit creates Goal with correct input binding`() {
            val goalSpec = GoalSpec(
                name = "generateReport",
                description = "Generate a report",
                outputTypeName = "Report",
            )

            val goal = goalSpec.emit(stepContext)

            assertEquals(1, goal.inputs.size)
            val input = goal.inputs.first()
            assertEquals("report", input.name)
            assertEquals("Report", input.type)
        }

        @Test
        fun `emit handles fully qualified output type name`() {
            val goalSpec = GoalSpec(
                name = "processData",
                description = "Process data",
                outputTypeName = "com.example.ProcessedData",
            )

            val goal = goalSpec.emit(stepContext)

            val input = goal.inputs.first()
            assertEquals("processedData", input.name)
            assertEquals("com.example.ProcessedData", input.type)
        }
    }

    @Nested
    inner class PromptedActionSpecEmit {

        @Test
        fun `emit creates Action with correct name and description`() {
            val actionSpec = PromptedActionSpec(
                name = "summarize",
                description = "Summarize text",
                inputTypeNames = setOf("TextInput"),
                outputTypeName = "Summary",
                prompt = "Summarize: {{textInput}}",
            )

            val action = actionSpec.emit(stepContext)

            assertEquals("summarize", action.name)
            assertEquals("Summarize text", action.description)
        }

        @Test
        fun `emit creates Action with correct input bindings`() {
            val actionSpec = PromptedActionSpec(
                name = "merge",
                description = "Merge inputs",
                inputTypeNames = setOf("DocumentA", "DocumentB"),
                outputTypeName = "MergedDocument",
                prompt = "Merge documents",
            )

            val action = actionSpec.emit(stepContext)

            assertEquals(2, action.inputs.size)
            assertTrue(action.inputs.any { it.name == "documentA" && it.type == "DocumentA" })
            assertTrue(action.inputs.any { it.name == "documentB" && it.type == "DocumentB" })
        }

        @Test
        fun `emit creates Action with correct output binding`() {
            val actionSpec = PromptedActionSpec(
                name = "analyze",
                description = "Analyze data",
                inputTypeNames = setOf("RawData"),
                outputTypeName = "AnalysisResult",
                prompt = "Analyze: {{rawData}}",
            )

            val action = actionSpec.emit(stepContext)

            assertEquals(1, action.outputs.size)
            val output = action.outputs.first()
            assertEquals("analysisResult", output.name)
            assertEquals("AnalysisResult", output.type)
        }

        @Test
        fun `emit creates Action with tool groups`() {
            val actionSpec = PromptedActionSpec(
                name = "research",
                description = "Research topic",
                inputTypeNames = setOf("Topic"),
                outputTypeName = "ResearchResults",
                prompt = "Research: {{topic}}",
                toolGroups = listOf("search", "web", "database"),
            )

            val action = actionSpec.emit(stepContext)

            assertEquals(3, action.toolGroups.size)
            val toolGroupNames = action.toolGroups.map { it.role }.toSet()
            assertEquals(setOf("search", "web", "database"), toolGroupNames)
        }

        @Test
        fun `emit creates Action with empty tool groups when none specified`() {
            val actionSpec = PromptedActionSpec(
                name = "simple",
                description = "Simple action",
                inputTypeNames = setOf("Input"),
                outputTypeName = "Output",
                prompt = "Process",
            )

            val action = actionSpec.emit(stepContext)

            assertTrue(action.toolGroups.isEmpty())
        }

        @Test
        fun `emit handles single character type names`() {
            val actionSpec = PromptedActionSpec(
                name = "transform",
                description = "Transform X to Y",
                inputTypeNames = setOf("X"),
                outputTypeName = "Y",
                prompt = "Transform",
            )

            val action = actionSpec.emit(stepContext)

            assertTrue(action.inputs.any { it.name == "x" && it.type == "X" })
            assertTrue(action.outputs.any { it.name == "y" && it.type == "Y" })
        }
    }

    @Nested
    inner class VariableNameFor {

        @Test
        fun `decapitalizes simple type name`() {
            assertEquals("userInput", PromptedActionSpec.variableNameFor("UserInput"))
        }

        @Test
        fun `handles already lowercase type name`() {
            assertEquals("string", PromptedActionSpec.variableNameFor("string"))
        }

        @Test
        fun `extracts simple name from fully qualified type`() {
            assertEquals("myClass", PromptedActionSpec.variableNameFor("com.example.pkg.MyClass"))
        }

        @Test
        fun `handles single character type name`() {
            assertEquals("a", PromptedActionSpec.variableNameFor("A"))
        }

        @Test
        fun `handles all caps type name`() {
            assertEquals("uUID", PromptedActionSpec.variableNameFor("UUID"))
        }
    }

    @Nested
    inner class StepSpecContextTests {

        @Test
        fun `context provides tool groups`() {
            val toolGroups = listOf(
                mockk<ToolGroupDescription>(),
                mockk<ToolGroupDescription>(),
            )
            val context = StepSpecContext(
                name = "with-tools",
                dataDictionary = dataDictionary,
                toolGroups = toolGroups,
            )

            assertEquals(2, context.toolGroups.size)
        }

        @Test
        fun `context provides data dictionary`() {
            val context = StepSpecContext(
                name = "with-dict",
                dataDictionary = dataDictionary,
                toolGroups = emptyList(),
            )

            assertSame(dataDictionary, context.dataDictionary)
        }
    }
}
