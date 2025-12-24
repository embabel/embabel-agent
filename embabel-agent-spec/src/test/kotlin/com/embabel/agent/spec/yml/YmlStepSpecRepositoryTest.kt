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
package com.embabel.agent.spec.yml

import com.embabel.agent.spec.model.GoalSpec
import com.embabel.agent.spec.model.PromptedActionSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class YmlStepSpecRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: YmlStepSpecRepository

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("yml-step-spec-test").toFile()
        repository = YmlStepSpecRepository(tempDir.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Nested
    inner class Save {

        @Test
        fun `save creates yml file with action spec`() {
            val actionSpec = PromptedActionSpec(
                name = "testAction",
                description = "Test action description",
                inputTypeNames = setOf("InputType"),
                outputTypeName = "OutputType",
                prompt = "Test prompt",
            )

            val saved = repository.save(actionSpec)

            assertEquals(actionSpec, saved)
            val savedFile = File(tempDir, "testAction.yml")
            assertTrue(savedFile.exists())
            val content = savedFile.readText()
            assertTrue(content.contains("testAction"), "Expected 'testAction' in content: $content")
            assertTrue(content.contains("stepType"), "Expected 'stepType' in content: $content")
        }

        @Test
        fun `save creates yml file with goal spec`() {
            val goalSpec = GoalSpec(
                name = "testGoal",
                description = "Test goal description",
                outputTypeName = "GoalOutput",
            )

            val saved = repository.save(goalSpec)

            assertEquals(goalSpec, saved)
            val savedFile = File(tempDir, "testGoal.yml")
            assertTrue(savedFile.exists())
            val content = savedFile.readText()
            assertTrue(content.contains("name: \"testGoal\""))
            assertTrue(content.contains("stepType: \"goal\""))
        }

        @Test
        fun `save creates directory if it does not exist`() {
            val newDir = File(tempDir, "nested/subdir")
            val newRepo = YmlStepSpecRepository(newDir.absolutePath)
            val actionSpec = PromptedActionSpec(
                name = "nestedAction",
                description = "Nested action",
                inputTypeNames = setOf("Input"),
                outputTypeName = "Output",
                prompt = "Prompt",
            )

            newRepo.save(actionSpec)

            assertTrue(newDir.exists())
            assertTrue(File(newDir, "nestedAction.yml").exists())
        }

        @Test
        fun `save overwrites existing file`() {
            val actionSpec1 = PromptedActionSpec(
                name = "overwriteTest",
                description = "First version",
                inputTypeNames = setOf("Input1"),
                outputTypeName = "Output1",
                prompt = "Prompt 1",
            )
            val actionSpec2 = PromptedActionSpec(
                name = "overwriteTest",
                description = "Second version",
                inputTypeNames = setOf("Input2"),
                outputTypeName = "Output2",
                prompt = "Prompt 2",
            )

            repository.save(actionSpec1)
            repository.save(actionSpec2)

            val content = File(tempDir, "overwriteTest.yml").readText()
            assertTrue(content.contains("Second version"))
            assertFalse(content.contains("First version"))
        }
    }

    @Nested
    inner class FindAll {

        @Test
        fun `findAll returns empty list when directory does not exist`() {
            val nonExistentDir = File(tempDir, "nonexistent")
            val repo = YmlStepSpecRepository(nonExistentDir.absolutePath)

            val result = repo.findAll().toList()

            assertTrue(result.isEmpty())
        }

        @Test
        fun `findAll returns empty list when directory is empty`() {
            val result = repository.findAll().toList()

            assertTrue(result.isEmpty())
        }

        @Test
        fun `findAll loads action spec from yml file`() {
            val actionSpec = PromptedActionSpec(
                name = "loadedAction",
                description = "Loaded action",
                inputTypeNames = setOf("LoadInput"),
                outputTypeName = "LoadOutput",
                prompt = "Load prompt",
            )
            repository.save(actionSpec)

            val result = repository.findAll().toList()

            assertEquals(1, result.size)
            val loaded = result.first() as PromptedActionSpec
            assertEquals("loadedAction", loaded.name)
            assertEquals("Loaded action", loaded.description)
            assertEquals(setOf("LoadInput"), loaded.inputTypeNames)
            assertEquals("LoadOutput", loaded.outputTypeName)
        }

        @Test
        fun `findAll loads goal spec from yml file`() {
            val goalSpec = GoalSpec(
                name = "loadedGoal",
                description = "Loaded goal",
                outputTypeName = "GoalOutput",
            )
            repository.save(goalSpec)

            val result = repository.findAll().toList()

            assertEquals(1, result.size)
            val loaded = result.first() as GoalSpec
            assertEquals("loadedGoal", loaded.name)
            assertEquals("Loaded goal", loaded.description)
            assertEquals("GoalOutput", loaded.outputTypeName)
        }

        @Test
        fun `findAll loads multiple specs`() {
            val action1 = PromptedActionSpec(
                name = "action1",
                description = "First action",
                inputTypeNames = setOf("Input1"),
                outputTypeName = "Output1",
                prompt = "Prompt 1",
            )
            val action2 = PromptedActionSpec(
                name = "action2",
                description = "Second action",
                inputTypeNames = setOf("Input2"),
                outputTypeName = "Output2",
                prompt = "Prompt 2",
            )
            repository.save(action1)
            repository.save(action2)

            val result = repository.findAll().toList()

            assertEquals(2, result.size)
            val names = result.map { it.name }.toSet()
            assertEquals(setOf("action1", "action2"), names)
        }

        @Test
        fun `findAll ignores non-yml files`() {
            val actionSpec = PromptedActionSpec(
                name = "validAction",
                description = "Valid",
                inputTypeNames = setOf("Input"),
                outputTypeName = "Output",
                prompt = "Prompt",
            )
            repository.save(actionSpec)
            File(tempDir, "readme.txt").writeText("Not a yml file")
            File(tempDir, "data.json").writeText("{}")

            val result = repository.findAll().toList()

            assertEquals(1, result.size)
            assertEquals("validAction", result.first().name)
        }

        @Test
        fun `findAll skips invalid yml files`() {
            val validAction = PromptedActionSpec(
                name = "validAction",
                description = "Valid",
                inputTypeNames = setOf("Input"),
                outputTypeName = "Output",
                prompt = "Prompt",
            )
            repository.save(validAction)
            File(tempDir, "invalid.yml").writeText("this is not valid yaml: [unclosed")

            val result = repository.findAll().toList()

            assertEquals(1, result.size)
            assertEquals("validAction", result.first().name)
        }

        @Test
        fun `findAll skips yml files that do not match StepSpec schema`() {
            val validAction = PromptedActionSpec(
                name = "validAction",
                description = "Valid",
                inputTypeNames = setOf("Input"),
                outputTypeName = "Output",
                prompt = "Prompt",
            )
            repository.save(validAction)
            File(tempDir, "notAStepSpec.yml").writeText("foo: bar\nbaz: 123")

            val result = repository.findAll().toList()

            assertEquals(1, result.size)
            assertEquals("validAction", result.first().name)
        }
    }

    @Nested
    inner class SaveAll {

        @Test
        fun `saveAll saves multiple specs`() {
            val specs = listOf(
                PromptedActionSpec(
                    name = "batchAction1",
                    description = "Batch 1",
                    inputTypeNames = setOf("Input"),
                    outputTypeName = "Output",
                    prompt = "Prompt",
                ),
                GoalSpec(
                    name = "batchGoal1",
                    description = "Batch goal",
                    outputTypeName = "GoalOutput",
                ),
            )

            val saved = repository.saveAll(specs).toList()

            assertEquals(2, saved.size)
            assertTrue(File(tempDir, "batchAction1.yml").exists())
            assertTrue(File(tempDir, "batchGoal1.yml").exists())
        }
    }

    @Nested
    inner class RoundTrip {

        @Test
        fun `action spec survives save and load round trip`() {
            val original = PromptedActionSpec(
                name = "roundTripAction",
                description = "Round trip test",
                inputTypeNames = setOf("TypeA", "TypeB"),
                outputTypeName = "ResultType",
                prompt = "Process {{typeA}} and {{typeB}}",
                toolGroups = listOf("search", "web"),
                nullable = true,
            )

            repository.save(original)
            val results = repository.findAll().toList()
            assertEquals(1, results.size, "Expected 1 result but got ${results.size}")
            val loaded = results.first() as PromptedActionSpec

            assertEquals(original.name, loaded.name)
            assertEquals(original.description, loaded.description)
            assertEquals(original.inputTypeNames, loaded.inputTypeNames)
            assertEquals(original.outputTypeName, loaded.outputTypeName)
            assertEquals(original.prompt, loaded.prompt)
            assertEquals(original.toolGroups, loaded.toolGroups)
            assertEquals(original.nullable, loaded.nullable)
        }

        @Test
        fun `goal spec survives save and load round trip`() {
            val original = GoalSpec(
                name = "roundTripGoal",
                description = "Goal round trip",
                outputTypeName = "FinalResult",
            )

            repository.save(original)
            val results = repository.findAll().toList()
            assertEquals(1, results.size, "Expected 1 result but got ${results.size}")
            val loaded = results.first() as GoalSpec

            assertEquals(original.name, loaded.name)
            assertEquals(original.description, loaded.description)
            assertEquals(original.outputTypeName, loaded.outputTypeName)
            assertEquals(original.stepType, loaded.stepType)
        }
    }
}
