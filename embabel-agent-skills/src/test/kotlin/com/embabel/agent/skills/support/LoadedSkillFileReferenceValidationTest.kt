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

import com.embabel.agent.skills.spec.SkillDefinition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LoadedSkillFileReferenceValidationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `validates successfully when all referenced files exist`() {
        val skillDir = createSkillWithFiles(
            instructions = "Use [the guide](references/guide.md) and run scripts/build.sh",
            files = mapOf(
                "references/guide.md" to "# Guide",
                "scripts/build.sh" to "#!/bin/bash\necho 'building'"
            )
        )

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "test-skill",
                description = "A test skill",
                instructions = "Use [the guide](references/guide.md) and run scripts/build.sh"
            ),
            basePath = skillDir
        )

        val result = skill.validateFileReferences()

        assertTrue(result.isValid)
        assertTrue(result.missingFiles.isEmpty())
    }

    @Test
    fun `fails validation when referenced file is missing`() {
        val skillDir = createSkillWithFiles(
            instructions = "See [missing](references/missing.md)",
            files = emptyMap()
        )

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "test-skill",
                description = "A test skill",
                instructions = "See [missing](references/missing.md)"
            ),
            basePath = skillDir
        )

        val result = skill.validateFileReferences()

        assertFalse(result.isValid)
        assertTrue(result.missingFiles.contains("references/missing.md"))
    }

    @Test
    fun `reports multiple missing files`() {
        val skillDir = createSkillWithFiles(
            instructions = """
                See [docs](references/docs.md).
                Run scripts/build.sh.
                View assets/logo.png.
            """.trimIndent(),
            files = mapOf(
                "scripts/build.sh" to "#!/bin/bash"
            )
        )

        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "test-skill",
                description = "A test skill",
                instructions = """
                    See [docs](references/docs.md).
                    Run scripts/build.sh.
                    View assets/logo.png.
                """.trimIndent()
            ),
            basePath = skillDir
        )

        val result = skill.validateFileReferences()

        assertFalse(result.isValid)
        assertEquals(2, result.missingFiles.size)
        assertTrue(result.missingFiles.contains("references/docs.md"))
        assertTrue(result.missingFiles.contains("assets/logo.png"))
    }

    @Test
    fun `validates successfully with no file references`() {
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "test-skill",
                description = "A test skill",
                instructions = "Just some instructions with no file references."
            ),
            basePath = tempDir
        )

        val result = skill.validateFileReferences()

        assertTrue(result.isValid)
    }

    @Test
    fun `validates successfully with null instructions`() {
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "test-skill",
                description = "A test skill",
                instructions = null
            ),
            basePath = tempDir
        )

        val result = skill.validateFileReferences()

        assertTrue(result.isValid)
    }

    @Test
    fun `detects path traversal attempts`() {
        val skill = LoadedSkill(
            skillMetadata = SkillDefinition(
                name = "test-skill",
                description = "A test skill",
                instructions = "See [evil](../../../etc/passwd)"
            ),
            basePath = tempDir
        )

        val result = skill.validateFileReferences()

        assertFalse(result.isValid)
        assertTrue(result.missingFiles.any { it.contains("invalid path") })
    }

    private fun createSkillWithFiles(
        instructions: String,
        files: Map<String, String>
    ): Path {
        val skillDir = tempDir.resolve("skill-${System.nanoTime()}")
        Files.createDirectories(skillDir)

        for ((path, content) in files) {
            val filePath = skillDir.resolve(path)
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, content)
        }

        return skillDir
    }
}
