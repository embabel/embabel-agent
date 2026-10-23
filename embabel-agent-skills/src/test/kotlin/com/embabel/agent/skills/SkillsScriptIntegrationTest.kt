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
package com.embabel.agent.skills

import com.embabel.agent.skills.script.ScriptExecutionResult
import com.embabel.agent.skills.script.ScriptLanguage
import com.embabel.agent.skills.script.SkillScript
import com.embabel.agent.skills.script.SkillScriptExecutionEngine
import com.embabel.agent.skills.spec.SkillDefinition
import com.embabel.agent.skills.support.LoadedSkill
import com.embabel.agent.skills.support.ResourceType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Integration tests verifying the full flow of Skills with script execution.
 */
class SkillsScriptIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `script tools available immediately with configured engine`() {
        // Create a skill with Python scripts
        val skillDir = tempDir.resolve("my-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/process.py"), "print('processing')")
        Files.writeString(skillDir.resolve("scripts/analyze.py"), "print('analyzing')")

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "Test skill with scripts",
                instructions = "Use the scripts",
            ),
            basePath = skillDir,
        )

        // Create engine that supports Python
        val engine = TestEngine(supportedLanguages = setOf(ScriptLanguage.PYTHON))

        // Create Skills with the engine configured
        val skills = Skills(
            name = "test",
            description = "test skills",
            skills = listOf(skill),
        ).withScriptExecutionEngine(engine)

        // Script tools should be available immediately (for PromptRunner compatibility)
        val tools = skills.tools()
        val scriptTools = tools.filter { it.definition.name.startsWith("test_my-skill_") }
        assertEquals(2, scriptTools.size, "Should have 2 script tools immediately")

        val toolNames = scriptTools.map { it.definition.name }.toSet()
        assertTrue("test_my-skill_process" in toolNames)
        assertTrue("test_my-skill_analyze" in toolNames)

        // Activate the skill to get instructions
        val activationResult = skills.activate("my-skill")

        // Activation should mention script tools
        assertTrue(activationResult.contains("Available Script Tools"),
            "Activation should mention available script tools. Got: $activationResult")
        assertTrue(activationResult.contains("test_my-skill_process"),
            "Activation should list process tool")
        assertTrue(activationResult.contains("test_my-skill_analyze"),
            "Activation should list analyze tool")
    }

    @Test
    fun `script tools only for supported languages`() {
        val skillDir = tempDir.resolve("multi-lang-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/script.py"), "print('python')")
        Files.writeString(skillDir.resolve("scripts/script.sh"), "echo 'bash'")
        Files.writeString(skillDir.resolve("scripts/script.js"), "console.log('js')")

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "multi-lang",
                description = "Multi-language skill",
            ),
            basePath = skillDir,
        )

        // Engine only supports Python
        val engine = TestEngine(supportedLanguages = setOf(ScriptLanguage.PYTHON))

        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        ).withScriptExecutionEngine(engine)

        skills.activate("multi-lang")

        val tools = skills.tools()
        val scriptTools = tools.filter { it.definition.name.startsWith("test_multi-lang_") }

        // Should only have Python tool
        assertEquals(1, scriptTools.size)
        assertEquals("test_multi-lang_script", scriptTools[0].definition.name)
    }

    @Test
    fun `chained withScriptExecutionEngine preserves skills`() {
        val skillDir = tempDir.resolve("chain-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/run.py"), "print('run')")

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(name = "chain-skill", description = "Test"),
            basePath = skillDir,
        )

        val engine = TestEngine(supportedLanguages = setOf(ScriptLanguage.PYTHON))

        // Chain multiple operations
        val skills = Skills(name = "test", description = "test")
            .withSkills(skill)
            .withScriptExecutionEngine(engine)

        // Skills should be preserved
        assertEquals(1, skills.skills.size)
        assertEquals("chain-skill", skills.skills[0].name)

        // Scripts should be findable
        val scripts = skills.skills[0].listResources(ResourceType.SCRIPTS)
        assertEquals(listOf("run.py"), scripts)

        // Activate and verify tools
        skills.activate("chain-skill")
        val tools = skills.tools()
        assertTrue(tools.any { it.definition.name == "test_chain-skill_run" })
    }

    @Test
    fun `debug copy chain with script engine`() {
        val skillDir = tempDir.resolve("debug-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/run.py"), "print('run')")

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(name = "debug-skill", description = "Debug"),
            basePath = skillDir,
        )

        val engine = TestEngine(supportedLanguages = setOf(ScriptLanguage.PYTHON, ScriptLanguage.BASH))

        // Step 1: Create initial Skills
        val skills1 = Skills("test", "test")
        println("Step 1 - initial tools: ${skills1.tools().map { it.definition.name }}")

        // Step 2: Add skill (simulates withGitHubUrl)
        val skills2 = skills1.withSkills(skill)
        println("Step 2 - after withSkills (no engine): ${skills2.tools().map { it.definition.name }}")
        println("Step 2 - skills: ${skills2.skills.map { it.name }}")

        // Step 3: Add engine - script tools should now appear
        val skills3 = skills2.withScriptExecutionEngine(engine)
        val toolsAfterEngine = skills3.tools()
        println("Step 3 - after withScriptExecutionEngine: ${toolsAfterEngine.map { it.definition.name }}")

        // Script tools should be available immediately after engine is configured
        assertTrue(toolsAfterEngine.any { it.definition.name == "test_debug-skill_run" },
            "Script tool should be available immediately after engine configured")

        // Step 4: Activate to get instructions
        val result = skills3.activate("debug-skill")
        println("Step 4 - activation result: $result")

        // Verify activation mentions tools
        assertTrue(result.contains("Available Script Tools"), "Activation should show script tools")
    }

    @Test
    fun `GitHub loaded skills have script tools after withScriptExecutionEngine`() {
        // Simulate how GitHub skills would be loaded - the skill directory exists
        val skillDir = tempDir.resolve("github-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/convert.py"), "print('converting')")
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: github-skill
            description: A skill from GitHub
            ---
            Instructions here.
        """.trimIndent())

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "github-skill",
                description = "A skill from GitHub",
                instructions = "Instructions here.",
            ),
            basePath = skillDir,
        )

        val engine = TestEngine(supportedLanguages = setOf(ScriptLanguage.PYTHON, ScriptLanguage.BASH))

        // Simulate the exact call pattern from user code:
        // .withGitHubUrl(...).withScriptExecutionEngine(engine)
        val skills = Skills("skills", "pdf skills")
            .withSkills(skill)  // Simulates what withGitHubUrl does internally
            .withScriptExecutionEngine(engine)

        // Verify scripts are accessible
        val loadedSkill = skills.skills[0]
        val scripts = loadedSkill.listResources(ResourceType.SCRIPTS)
        println("Scripts found: $scripts")
        assertEquals(listOf("convert.py"), scripts)

        // Verify script tools can be generated
        val scriptTools = loadedSkill.getScriptTools(engine)
        println("Script tools: ${scriptTools.map { it.definition.name }}")
        assertEquals(1, scriptTools.size)
        assertEquals("github-skill_convert", scriptTools[0].definition.name)

        // Activate and check
        val activationResult = skills.activate("github-skill")
        println("Activation result: $activationResult")
        assertTrue(activationResult.contains("Available Script Tools"),
            "Should mention script tools. Got: $activationResult")

        // Verify tools() returns script tools after activation
        val allTools = skills.tools()
        val scriptToolNames = allTools.filter { it.definition.name.startsWith("skills_github-skill_") }
            .map { it.definition.name }
        println("Tools after activation: $scriptToolNames")
        assertTrue("skills_github-skill_convert" in scriptToolNames)
    }

    private class TestEngine(
        private val supportedLanguages: Set<ScriptLanguage>
    ) : SkillScriptExecutionEngine {
        override fun supportedLanguages() = supportedLanguages
        override fun execute(script: SkillScript, args: List<String>, stdin: String?, inputFiles: List<Path>) =
            ScriptExecutionResult.Success("ok", "", 0, 1.milliseconds)
    }
}
