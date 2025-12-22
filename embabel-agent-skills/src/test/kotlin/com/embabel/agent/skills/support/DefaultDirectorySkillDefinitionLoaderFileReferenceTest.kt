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
package com.embabel.agent.skills.support

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DefaultDirectorySkillDefinitionLoaderFileReferenceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `throws exception when referenced file is missing with validation enabled`() {
        val skillDir = createSkillDirectory(
            """
            ---
            name: my-skill
            description: A skill with missing reference
            ---
            See [the docs](references/missing.md) for details.
            """.trimIndent()
        )

        val loader = DefaultDirectorySkillDefinitionLoader(validateFileReferences = true)

        val exception = assertThrows(SkillLoadException::class.java) {
            loader.load(skillDir)
        }

        assertTrue(exception.message!!.contains("references missing files"))
        assertTrue(exception.message!!.contains("references/missing.md"))
    }

    @Test
    fun `loads successfully when referenced files exist`() {
        val skillDir = createSkillDirectory(
            """
            ---
            name: my-skill
            description: A skill with valid references
            ---
            See [the docs](references/guide.md) and run scripts/build.sh.
            """.trimIndent()
        )

        // Create the referenced files
        Files.createDirectories(skillDir.resolve("references"))
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("references/guide.md"), "# Guide")
        Files.writeString(skillDir.resolve("scripts/build.sh"), "#!/bin/bash")

        val loader = DefaultDirectorySkillDefinitionLoader(validateFileReferences = true)

        val skill = loader.load(skillDir)

        assertEquals("my-skill", skill.name)
    }

    @Test
    fun `loads successfully without validation when flag is disabled`() {
        val skillDir = createSkillDirectory(
            """
            ---
            name: my-skill
            description: A skill with missing reference
            ---
            See [the docs](references/missing.md) for details.
            """.trimIndent()
        )

        val loader = DefaultDirectorySkillDefinitionLoader(validateFileReferences = false)

        val skill = loader.load(skillDir)

        assertEquals("my-skill", skill.name)
    }

    @Test
    fun `loads successfully with no file references`() {
        val skillDir = createSkillDirectory(
            """
            ---
            name: my-skill
            description: A skill without file references
            ---
            Just some plain instructions.
            """.trimIndent()
        )

        val loader = DefaultDirectorySkillDefinitionLoader(validateFileReferences = true)

        val skill = loader.load(skillDir)

        assertEquals("my-skill", skill.name)
    }

    @Test
    fun `reports multiple missing files in exception message`() {
        val skillDir = createSkillDirectory(
            """
            ---
            name: my-skill
            description: A skill with multiple missing references
            ---
            See [docs](references/docs.md) and run scripts/build.sh.
            """.trimIndent()
        )

        val loader = DefaultDirectorySkillDefinitionLoader(validateFileReferences = true)

        val exception = assertThrows(SkillLoadException::class.java) {
            loader.load(skillDir)
        }

        assertTrue(exception.message!!.contains("references/docs.md"))
        assertTrue(exception.message!!.contains("scripts/build.sh"))
    }

    private fun createSkillDirectory(skillMdContent: String): Path {
        val skillDir = tempDir.resolve("skill-${System.nanoTime()}")
        Files.createDirectories(skillDir)
        Files.writeString(skillDir.resolve("SKILL.md"), skillMdContent)
        return skillDir
    }
}
