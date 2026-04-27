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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class SkillsTest {

    @TempDir
    lateinit var tempDir: Path

    // activate() tests

    @Test
    fun `activate returns skill instructions`() {
        val skillDir = createSkillDirectory("test-skill")
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "test-skill",
                description = "A test skill",
                instructions = "# Test Instructions\n\nDo the thing.",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test skills",
            skills = listOf(skill),
        )

        val result = skills.activate("test-skill")

        assertTrue(result.contains("# Skill: test-skill"))
        assertTrue(result.contains("# Test Instructions"))
        assertTrue(result.contains("Do the thing."))
    }

    @Test
    fun `activate returns error for unknown skill`() {
        val skills = Skills(
            name = "test",
            description = "test skills",
            skills = emptyList(),
        )

        val result = skills.activate("unknown-skill")

        assertTrue(result.contains("Skill not found"))
        assertTrue(result.contains("unknown-skill"))
    }

    @Test
    fun `activate is case insensitive`() {
        val skillDir = createSkillDirectory("my-skill")
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
                instructions = "Instructions here",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.activate("MY-SKILL")

        assertTrue(result.contains("# Skill: my-skill"))
        assertTrue(result.contains("Instructions here"))
    }

    @Test
    fun `activate accepts underscore for a hyphenated skill name`() {
        // Tool surfaces sanitize hyphens to underscores when exposing skills
        // as top-level tools (e.g. via withUnfolding). The model sees
        // 'github_workflows' in its tool list and naturally passes that
        // string back to activate. We must accept it.
        val skillDir = createSkillDirectory("github-workflows")
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "github-workflows",
                description = "GitHub workflow knowledge",
                instructions = "Pagination tips here",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.activate("github_workflows")

        assertTrue(result.contains("# Skill: github-workflows"))
        assertTrue(result.contains("Pagination tips here"))
    }

    @Test
    fun `activate accepts hyphen for an underscored skill name`() {
        // Reverse direction — symmetry. A skill authored with underscores
        // must still resolve when the model passes the hyphenated form.
        val skillDir = createSkillDirectory("my_helper")
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my_helper",
                description = "A helper",
                instructions = "Helper instructions",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.activate("my-helper")

        assertTrue(result.contains("# Skill: my_helper"))
        assertTrue(result.contains("Helper instructions"))
    }

    @Test
    fun `activate combines separator tolerance with case insensitivity`() {
        val skillDir = createSkillDirectory("my-mixed-skill")
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-mixed-skill",
                description = "Mixed skill",
                instructions = "Mixed instructions",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.activate("MY_Mixed_Skill")

        assertTrue(result.contains("# Skill: my-mixed-skill"))
        assertTrue(result.contains("Mixed instructions"))
    }

    @Test
    fun `activate handles skill without instructions`() {
        val skillDir = createSkillDirectory("no-instructions")
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "no-instructions",
                description = "A skill without instructions",
                instructions = null,
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.activate("no-instructions")

        assertTrue(result.contains("No instructions available"))
    }

    @Test
    fun `activate includes resource information when available`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "resource-skill",
                description = "A skill with resources",
                instructions = "Use the scripts",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.activate("resource-skill")

        assertTrue(result.contains("## Available Resources"))
        assertTrue(result.contains("Scripts"))
        assertTrue(result.contains("References"))
    }

    // listResources() tests

    @Test
    fun `listResources returns files in scripts directory`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.listResources("my-skill", "scripts")

        assertTrue(result.contains("build.sh"))
        assertTrue(result.contains("deploy.py"))
    }

    @Test
    fun `listResources returns files in references directory`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.listResources("my-skill", "references")

        assertTrue(result.contains("api-docs.md"))
    }

    @Test
    fun `listResources returns error for unknown skill`() {
        val skills = Skills(
            name = "test",
            description = "test",
            skills = emptyList(),
        )

        val result = skills.listResources("unknown", "scripts")

        assertTrue(result.contains("Skill not found"))
    }

    @Test
    fun `listResources returns message for missing directory`() {
        val skillDir = createSkillDirectory("my-skill")
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.listResources("my-skill", "assets")

        assertTrue(result.contains("No assets found"))
    }

    // readResource() tests

    @Test
    fun `readResource returns file content`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.readResource("my-skill", "scripts", "build.sh")

        assertEquals("#!/bin/bash\necho 'Building...'", result)
    }

    @Test
    fun `readResource returns error for unknown skill`() {
        val skills = Skills(
            name = "test",
            description = "test",
            skills = emptyList(),
        )

        val result = skills.readResource("unknown", "scripts", "file.sh")

        assertTrue(result.contains("Skill not found"))
    }

    @Test
    fun `readResource returns error for missing file`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.readResource("my-skill", "scripts", "nonexistent.sh")

        assertTrue(result.contains("File not found"))
    }

    @Test
    fun `readResource prevents path traversal`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.readResource("my-skill", "scripts", "../../../etc/passwd")

        assertTrue(result.contains("File not found"))
    }

    @Test
    fun `readResource is case insensitive for resource type`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val result = skills.readResource("my-skill", "SCRIPTS", "build.sh")

        assertEquals("#!/bin/bash\necho 'Building...'", result)
    }

    // notes() tests

    @Test
    fun `notes returns formatted skill list`() {
        val skillDir = createSkillDirectory("skill-a")
        val skillDir2 = createSkillDirectory("skill-b")
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(
                LoadedSkill(
                    skillMetadata = SkillDefinition(name = "skill-a", description = "First skill"),
                    basePath = skillDir,
                ),
                LoadedSkill(
                    skillMetadata = SkillDefinition(name = "skill-b", description = "Second skill"),
                    basePath = skillDir2,
                ),
            ),
        )

        val notes = skills.notes()

        assertTrue(notes.contains("available_skills"))
        assertTrue(notes.contains("skill-a"))
        assertTrue(notes.contains("skill-b"))
        assertTrue(notes.contains("First skill"))
        assertTrue(notes.contains("Second skill"))
    }

    // Script tools tests

    @Test
    fun `tools includes script tools for all skills with configured engine`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        ).withScriptExecutionEngine(TestExecutionEngine())

        val tools = skills.tools()
        val toolNames = tools.map { it.definition.name }

        // Script tools should be available immediately (for PromptRunner compatibility)
        assertTrue(toolNames.contains("my-skill_build"))
        assertTrue(toolNames.contains("my-skill_deploy"))
    }

    @Test
    fun `tools does not include script tools without configured engine`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
            ),
            basePath = skillDir,
        )
        // No withScriptExecutionEngine call - uses NoOpExecutionEngine
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        )

        val tools = skills.tools()
        val toolNames = tools.map { it.definition.name }

        // No script tools because NoOpExecutionEngine supports no languages
        assertFalse(toolNames.any { it.startsWith("my-skill_") })
    }

    @Test
    fun `activate mentions available script tools`() {
        val skillDir = createSkillWithResources()
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "my-skill",
                description = "A skill",
                instructions = "Do the thing",
            ),
            basePath = skillDir,
        )
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill),
        ).withScriptExecutionEngine(TestExecutionEngine())

        val result = skills.activate("my-skill")

        assertTrue(result.contains("Available Script Tools"))
        assertTrue(result.contains("my-skill_build"))
        assertTrue(result.contains("my-skill_deploy"))
    }

    @Test
    fun `script tools available for all skills with engine`() {
        val skillDir1 = createSkillWithResources()
        val skillDir2 = tempDir.resolve("skill-2")
        Files.createDirectories(skillDir2.resolve("scripts"))
        Files.writeString(skillDir2.resolve("scripts/test.sh"), "#!/bin/bash\necho test")

        val skill1 = LoadedSkill(
            skillMetadata = SkillDefinition(name = "skill-1", description = "First"),
            basePath = skillDir1,
        )
        val skill2 = LoadedSkill(
            skillMetadata = SkillDefinition(name = "skill-2", description = "Second"),
            basePath = skillDir2,
        )

        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(skill1, skill2),
        ).withScriptExecutionEngine(TestExecutionEngine())

        val tools = skills.tools()
        val toolNames = tools.map { it.definition.name }

        // All skill scripts should be available (no activation required for tools)
        assertTrue(toolNames.contains("skill-1_build"))
        assertTrue(toolNames.contains("skill-2_test"))
    }

    // asIndividualReferences() tests

    @Test
    fun `asIndividualReferences returns one reference per skill`() {
        val skillDir1 = createSkillDirectory("skill-a")
        val skillDir2 = createSkillDirectory("skill-b")
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(
                LoadedSkill(
                    skillMetadata = SkillDefinition(name = "skill-a", description = "First skill"),
                    basePath = skillDir1,
                ),
                LoadedSkill(
                    skillMetadata = SkillDefinition(name = "skill-b", description = "Second skill"),
                    basePath = skillDir2,
                ),
            ),
        )

        val refs = skills.asIndividualReferences()

        assertEquals(2, refs.size)
        assertEquals("skill-a", refs[0].name)
        assertEquals("First skill", refs[0].description)
        assertEquals("skill-b", refs[1].name)
        assertEquals("Second skill", refs[1].description)
    }

    @Test
    fun `asIndividualReferences returns empty list when no skills`() {
        val skills = Skills(name = "test", description = "test", skills = emptyList())

        val refs = skills.asIndividualReferences()

        assertTrue(refs.isEmpty())
    }

    @Test
    fun `asIndividualReferences each reference has tools`() {
        val skillDir = createSkillDirectory("my-skill")
        val skills = Skills(
            name = "test",
            description = "test",
            skills = listOf(
                LoadedSkill(
                    skillMetadata = SkillDefinition(name = "my-skill", description = "A skill"),
                    basePath = skillDir,
                ),
            ),
        )

        val refs = skills.asIndividualReferences()
        val tools = refs[0].tools()

        // Should have tools (at minimum the unfolding wrapper)
        assertTrue(tools.isNotEmpty(), "Each skill reference should have tools")
    }

    // Helper methods

    private fun createSkillDirectory(name: String): Path {
        val skillDir = tempDir.resolve(name)
        Files.createDirectories(skillDir)
        return skillDir
    }

    private fun createSkillWithResources(): Path {
        val skillDir = tempDir.resolve("test-skill-${System.nanoTime()}")
        val scriptsDir = skillDir.resolve("scripts")
        val referencesDir = skillDir.resolve("references")

        Files.createDirectories(scriptsDir)
        Files.createDirectories(referencesDir)

        Files.writeString(scriptsDir.resolve("build.sh"), "#!/bin/bash\necho 'Building...'")
        Files.writeString(scriptsDir.resolve("deploy.py"), "#!/usr/bin/env python\nprint('Deploying')")
        Files.writeString(referencesDir.resolve("api-docs.md"), "# API Documentation\n\nEndpoints...")

        return skillDir
    }

    private class TestExecutionEngine : SkillScriptExecutionEngine {
        override fun supportedLanguages() = ScriptLanguage.entries.toSet()
        override fun execute(script: SkillScript, args: List<String>, stdin: String?, inputFiles: List<Path>) =
            ScriptExecutionResult.Success("ok", "", 0, 1.milliseconds)
    }
}
