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

import com.embabel.agent.skills.support.DefaultDirectorySkillDefinitionLoader
import com.embabel.agent.skills.support.GitHubSkillDefinitionLoader
import com.embabel.agent.skills.support.SkillLoadException
import com.embabel.coding.tools.git.ClonedRepositoryReference
import com.embabel.coding.tools.git.RepositoryReferenceProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GitHubDirectorySkillDefinitionLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fromGitHub loads skills from repository root`() {
        val repoDir = createMockRepoWithSkills(
            "skill-one" to """
                ---
                name: skill-one
                description: First skill
                ---
                Instructions for skill one.
            """.trimIndent(),
            "skill-two" to """
                ---
                name: skill-two
                description: Second skill
                ---
            """.trimIndent()
        )

        val mockProvider = mockRepositoryProvider(repoDir)
        val loader = GitHubSkillDefinitionLoader(
            directorySkillDefinitionLoader = DefaultDirectorySkillDefinitionLoader(),
            repositoryReferenceProvider = mockProvider
        )

        val skills = loader.fromGitHub("owner", "repo")

        assertEquals(2, skills.size)
        assertTrue(skills.any { it.name == "skill-one" && it.description == "First skill" })
        assertTrue(skills.any { it.name == "skill-two" && it.description == "Second skill" })

        verify {
            mockProvider.cloneRepository(
                url = "https://github.com/owner/repo.git",
                description = any(),
                branch = null,
                depth = 1
            )
        }
    }

    @Test
    fun `fromGitHub loads single skill from repository root`() {
        val repoDir = tempDir.resolve("single-skill-repo")
        Files.createDirectories(repoDir)
        Files.writeString(
            repoDir.resolve("SKILL.md"),
            """
            ---
            name: root-skill
            description: Skill at root level
            ---
            Root skill instructions.
            """.trimIndent()
        )

        val mockProvider = mockRepositoryProvider(repoDir)
        val loader = GitHubSkillDefinitionLoader(
            repositoryReferenceProvider = mockProvider
        )

        val skills = loader.fromGitHub("owner", "single-skill")

        assertEquals(1, skills.size)
        assertEquals("root-skill", skills[0].name)
        assertEquals("Skill at root level", skills[0].description)
    }

    @Test
    fun `fromGitHub with skillsPath loads from subdirectory`() {
        val repoDir = tempDir.resolve("nested-skills-repo")
        val skillsDir = repoDir.resolve("skills")
        Files.createDirectories(skillsDir)

        createSkillInDirectory(skillsDir.resolve("nested-skill"), "nested-skill", "Nested skill")

        val mockProvider = mockRepositoryProvider(repoDir)
        val loader = GitHubSkillDefinitionLoader(repositoryReferenceProvider = mockProvider)

        val skills = loader.fromGitHub("owner", "repo", skillsPath = "skills")

        assertEquals(1, skills.size)
        assertEquals("nested-skill", skills[0].name)
    }

    @Test
    fun `fromGitHub with branch parameter passes branch to provider`() {
        val repoDir = createMockRepoWithSkills(
            "branch-skill" to """
                ---
                name: branch-skill
                description: Skill from branch
                ---
            """.trimIndent()
        )

        val mockProvider = mockRepositoryProvider(repoDir)
        val loader = GitHubSkillDefinitionLoader(repositoryReferenceProvider = mockProvider)

        loader.fromGitHub("owner", "repo", branch = "develop")

        verify {
            mockProvider.cloneRepository(
                url = "https://github.com/owner/repo.git",
                description = any(),
                branch = "develop",
                depth = 1
            )
        }
    }

    @Test
    fun `loadSkillFromGitHub loads single skill by path`() {
        val repoDir = tempDir.resolve("multi-skill-repo")
        val skillDir = repoDir.resolve("skills/my-skill")
        Files.createDirectories(skillDir)
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            """
            ---
            name: my-skill
            description: A specific skill
            ---
            Skill instructions.
            """.trimIndent()
        )

        val mockProvider = mockRepositoryProvider(repoDir)
        val loader = GitHubSkillDefinitionLoader(repositoryReferenceProvider = mockProvider)

        val skill = loader.loadSkillFromGitHub("owner", "repo", "skills/my-skill")

        assertEquals("my-skill", skill.name)
        assertEquals("A specific skill", skill.description)
    }

    @Test
    fun `fromGitUrl loads skills from any Git URL`() {
        val repoDir = createMockRepoWithSkills(
            "git-skill" to """
                ---
                name: git-skill
                description: Skill from git URL
                ---
            """.trimIndent()
        )

        val mockProvider = mockRepositoryProvider(repoDir, expectedUrl = "https://gitlab.com/owner/repo.git")
        val loader = GitHubSkillDefinitionLoader(repositoryReferenceProvider = mockProvider)

        val skills = loader.fromGitUrl("https://gitlab.com/owner/repo.git")

        assertEquals(1, skills.size)
        assertEquals("git-skill", skills[0].name)
    }

    @Test
    fun `fromGitHub throws SkillLoadException on clone failure`() {
        val mockProvider = mockk<RepositoryReferenceProvider>()
        every {
            mockProvider.cloneRepository(any(), any(), any(), any())
        } throws RuntimeException("Network error")

        val loader = GitHubSkillDefinitionLoader(repositoryReferenceProvider = mockProvider)

        val exception = assertThrows<SkillLoadException> {
            loader.fromGitHub("owner", "nonexistent")
        }

        assertTrue(exception.message!!.contains("Failed to clone"))
        assertTrue(exception.message!!.contains("owner/nonexistent"))
    }

    @Test
    fun `fromGitHub returns empty list when no skills found`() {
        val emptyRepoDir = tempDir.resolve("empty-repo")
        Files.createDirectories(emptyRepoDir)
        // Create some non-skill files
        Files.writeString(emptyRepoDir.resolve("README.md"), "# README")

        val mockProvider = mockRepositoryProvider(emptyRepoDir)
        val loader = GitHubSkillDefinitionLoader(repositoryReferenceProvider = mockProvider)

        val skills = loader.fromGitHub("owner", "empty-repo")

        assertTrue(skills.isEmpty())
    }

    @Test
    fun `create factory method returns new instance`() {
        val loader = GitHubSkillDefinitionLoader.create()
        assertNotNull(loader)
    }

    // Helper methods

    private fun createMockRepoWithSkills(vararg skills: Pair<String, String>): Path {
        val repoDir = tempDir.resolve("mock-repo-${System.nanoTime()}")
        Files.createDirectories(repoDir)

        skills.forEach { (name, content) ->
            val skillDir = repoDir.resolve(name)
            Files.createDirectories(skillDir)
            Files.writeString(skillDir.resolve("SKILL.md"), content)
        }

        return repoDir
    }

    private fun createSkillInDirectory(
        directory: Path,
        name: String,
        description: String,
    ) {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("SKILL.md"),
            """
            ---
            name: $name
            description: $description
            ---
            """.trimIndent()
        )
    }

    private fun mockRepositoryProvider(
        repoDir: Path,
        expectedUrl: String? = null,
    ): RepositoryReferenceProvider {
        val mockProvider = mockk<RepositoryReferenceProvider>()
        val mockClonedRepo = ClonedRepositoryReference(
            url = expectedUrl ?: "https://github.com/owner/repo.git",
            description = "Mock repository",
            localPath = repoDir,
            deleteOnClose = false, // Don't delete our temp files
        )

        every {
            mockProvider.cloneRepository(
                url = expectedUrl ?: any(),
                description = any(),
                branch = any(),
                depth = any()
            )
        } returns mockClonedRepo

        return mockProvider
    }
}
