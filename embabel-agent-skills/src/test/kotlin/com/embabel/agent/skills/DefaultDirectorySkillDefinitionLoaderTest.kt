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
package com.embabel.agent.skills

import com.embabel.agent.skills.support.DefaultDirectorySkillDefinitionLoader
import com.embabel.agent.skills.support.SkillLoadException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DefaultDirectorySkillDefinitionLoaderTest {

    private val loader = DefaultDirectorySkillDefinitionLoader()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load skill with minimal frontmatter`() {
        val skillDir = createSkillDirectory(
            "my-skill",
            """
            ---
            name: my-skill
            description: A test skill
            ---
            """.trimIndent()
        )

        val skill = loader.load(skillDir)

        assertEquals("my-skill", skill.name)
        assertEquals("A test skill", skill.description)
        assertNull(skill.instructions)
    }

    @Test
    fun `load skill with all frontmatter fields`() {
        val skillDir = createSkillDirectory(
            "complete-skill",
            """
            ---
            name: complete-skill
            description: A complete skill with all fields
            license: Apache-2.0
            compatibility: Requires Python 3.9+
            metadata:
              author: test
              version: "1.0"
            allowed-tools: Bash(git:*) Read
            ---
            """.trimIndent()
        )

        val skill = loader.load(skillDir)

        assertEquals("complete-skill", skill.name)
        assertEquals("A complete skill with all fields", skill.description)
        assertEquals("Apache-2.0", skill.license)
        assertEquals("Requires Python 3.9+", skill.compatibility)
        assertEquals(mapOf("author" to "test", "version" to "1.0"), skill.metadata)
        assertEquals("Bash(git:*) Read", skill.allowedTools)
    }

    @Test
    fun `load skill with instructions`() {
        val skillDir = createSkillDirectory(
            "documented-skill",
            """
            ---
            name: documented-skill
            description: A skill with instructions
            ---
            # How to use this skill

            This skill does amazing things.

            ## Examples

            Here are some examples.
            """.trimIndent()
        )

        val skill = loader.load(skillDir)

        assertEquals("documented-skill", skill.name)
        assertNotNull(skill.instructions)
        assertTrue(skill.instructions!!.contains("# How to use this skill"))
        assertTrue(skill.instructions!!.contains("## Examples"))
    }

    @Test
    fun `load skill with case-insensitive filename`() {
        val skillDir = tempDir.resolve("case-skill")
        Files.createDirectories(skillDir)
        Files.writeString(
            skillDir.resolve("skill.MD"),
            """
            ---
            name: case-skill
            description: Lowercase filename
            ---
            """.trimIndent()
        )

        val skill = loader.load(skillDir)

        assertEquals("case-skill", skill.name)
    }

    @Test
    fun `load throws exception for non-directory path`() {
        val file = tempDir.resolve("not-a-directory.txt")
        Files.writeString(file, "content")

        val exception = assertThrows<SkillLoadException> {
            loader.load(file)
        }

        assertTrue(exception.message!!.contains("not a directory"))
    }

    @Test
    fun `load throws exception when SKILL md is missing`() {
        val emptyDir = tempDir.resolve("empty-skill")
        Files.createDirectories(emptyDir)

        val exception = assertThrows<SkillLoadException> {
            loader.load(emptyDir)
        }

        assertTrue(exception.message!!.contains("No SKILL.md"))
    }

    @Test
    fun `load throws exception when frontmatter is missing`() {
        val skillDir = createSkillDirectory(
            "no-frontmatter",
            """
            # Just markdown
            No frontmatter here
            """.trimIndent()
        )

        val exception = assertThrows<SkillLoadException> {
            loader.load(skillDir)
        }

        assertTrue(exception.message!!.contains("must start with YAML frontmatter"))
    }

    @Test
    fun `load throws exception when frontmatter is not closed`() {
        val skillDir = createSkillDirectory(
            "unclosed-frontmatter",
            """
            ---
            name: unclosed
            description: Missing closing delimiter

            # Instructions without closing ---
            """.trimIndent()
        )

        val exception = assertThrows<SkillLoadException> {
            loader.load(skillDir)
        }

        assertTrue(exception.message!!.contains("not closed"))
    }

    @Test
    fun `loadAll loads multiple skills from parent directory`() {
        createSkillDirectory(
            "skill-one",
            """
            ---
            name: skill-one
            description: First skill
            ---
            """.trimIndent()
        )

        createSkillDirectory(
            "skill-two",
            """
            ---
            name: skill-two
            description: Second skill
            ---
            """.trimIndent()
        )

        // Create a non-skill directory (no SKILL.md)
        Files.createDirectories(tempDir.resolve("not-a-skill"))

        val skills = loader.loadAll(tempDir)

        assertEquals(2, skills.size)
        assertTrue(skills.any { it.name == "skill-one" })
        assertTrue(skills.any { it.name == "skill-two" })
    }

    @Test
    fun `loadAll returns empty list when no skills found`() {
        val emptyParent = tempDir.resolve("empty-parent")
        Files.createDirectories(emptyParent)

        val skills = loader.loadAll(emptyParent)

        assertTrue(skills.isEmpty())
    }

    @Test
    fun `loadAll throws exception for non-directory path`() {
        val file = tempDir.resolve("not-a-directory.txt")
        Files.writeString(file, "content")

        val exception = assertThrows<SkillLoadException> {
            loader.loadAll(file)
        }

        assertTrue(exception.message!!.contains("not a directory"))
    }

    @Test
    fun `load ignores unknown frontmatter fields`() {
        val skillDir = createSkillDirectory(
            "extra-fields",
            """
            ---
            name: extra-fields
            description: Has extra fields
            unknown-field: should be ignored
            another-unknown: also ignored
            ---
            """.trimIndent()
        )

        val skill = loader.load(skillDir)

        assertEquals("extra-fields", skill.name)
        assertEquals("Has extra fields", skill.description)
    }

    @Test
    fun `load preserves whitespace in instructions`() {
        val skillDir = createSkillDirectory(
            "whitespace-skill",
            """
            ---
            name: whitespace-skill
            description: Preserves whitespace
            ---
            Line 1

            Line 3 (after blank line)


            Line 6 (after two blank lines)
            """.trimIndent()
        )

        val skill = loader.load(skillDir)

        assertNotNull(skill.instructions)
        assertTrue(skill.instructions!!.contains("\n\nLine 3"))
        assertTrue(skill.instructions!!.contains("\n\n\nLine 6"))
    }

    private fun createSkillDirectory(
        name: String,
        skillMdContent: String,
    ): Path {
        val skillDir = tempDir.resolve(name)
        Files.createDirectories(skillDir)
        Files.writeString(skillDir.resolve("SKILL.md"), skillMdContent)
        return skillDir
    }
}
