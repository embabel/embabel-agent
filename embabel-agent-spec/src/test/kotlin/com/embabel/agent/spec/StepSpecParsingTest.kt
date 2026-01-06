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
package com.embabel.agent.spec

import com.embabel.agent.spec.model.GoalSpec
import com.embabel.agent.spec.model.PromptedActionSpec
import com.embabel.agent.spec.model.StepSpec
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StepSpecParsingTest {

    private lateinit var yamlMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    }

    @Nested
    inner class PromptedActionDefinitionParsing {

        @Test
        fun `parse simple action from yaml`() {
            val yaml = loadYamlResource("yml/simple-action.yml")

            val result = yamlMapper.readValue(yaml, StepSpec::class.java)

            assertInstanceOf(PromptedActionSpec::class.java, result)
            val action = result as PromptedActionSpec
            assertEquals("summarize", action.name)
            assertEquals("Summarize the input text", action.description)
            assertEquals(setOf("UserInput"), action.inputTypeNames)
            assertEquals("Summary", action.outputTypeName)
            assertTrue(action.prompt.contains("Summarize the following"))
            assertEquals("action", action.stepType)
            assertFalse(action.nullable)
            assertTrue(action.toolGroups.isEmpty())
        }

        @Test
        fun `parse action with llm options from yaml`() {
            val yaml = loadYamlResource("yml/action-with-llm-options.yml")

            val result = yamlMapper.readValue(yaml, StepSpec::class.java)

            assertInstanceOf(PromptedActionSpec::class.java, result)
            val action = result as PromptedActionSpec
            assertEquals("analyze", action.name)
            assertEquals("Analyze the input data with specific LLM settings", action.description)
            assertEquals(0.7, action.llm.temperature)
            assertEquals("gpt-4", action.llm.model)
            assertEquals(setOf("RawData"), action.inputTypeNames)
            assertEquals("Analysis", action.outputTypeName)
            assertEquals(listOf("search", "web"), action.toolGroups)
            assertTrue(action.nullable)
        }

        @Test
        fun `parse multi-input action from yaml`() {
            val yaml = loadYamlResource("yml/multi-input-action.yml")

            val result = yamlMapper.readValue(yaml, StepSpec::class.java)

            assertInstanceOf(PromptedActionSpec::class.java, result)
            val action = result as PromptedActionSpec
            assertEquals("compareDocuments", action.name)
            assertEquals(setOf("Document", "ReferenceDocument"), action.inputTypeNames)
            assertEquals("ComparisonResult", action.outputTypeName)
            assertTrue(action.prompt.contains("Compare the following documents"))
            assertTrue(action.prompt.contains("{{document}}"))
            assertTrue(action.prompt.contains("{{referenceDocument}}"))
        }
    }

    @Nested
    inner class GoalDefinitionParsing {

        @Test
        fun `parse simple goal from yaml`() {
            val yaml = loadYamlResource("yml/simple-goal.yml")

            val result = yamlMapper.readValue(yaml, StepSpec::class.java)

            assertInstanceOf(GoalSpec::class.java, result)
            val goal = result as GoalSpec
            assertEquals("generateReport", goal.name)
            assertEquals("Generate a complete report from analysis", goal.description)
            assertEquals("Report", goal.outputTypeName)
            assertEquals("goal", goal.stepType)
        }
    }

    @Nested
    inner class VariableNameGeneration {

        @Test
        fun `variableNameFor decapitalizes simple type name`() {
            val result = PromptedActionSpec.variableNameFor("UserInput")
            assertEquals("userInput", result)
        }

        @Test
        fun `variableNameFor handles fully qualified type name`() {
            val result = PromptedActionSpec.variableNameFor("com.example.UserInput")
            assertEquals("userInput", result)
        }

        @Test
        fun `variableNameFor handles single character type name`() {
            val result = PromptedActionSpec.variableNameFor("X")
            assertEquals("x", result)
        }
    }

    private fun loadYamlResource(resourcePath: String): String {
        return javaClass.classLoader.getResourceAsStream(resourcePath)
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
    }
}
