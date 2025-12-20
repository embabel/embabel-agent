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

import com.embabel.coding.tools.git.RepositoryReferenceProvider
import java.nio.file.Path

/**
 * Loads skills from GitHub repositories.
 *
 * Clones a GitHub repository and loads all skills from it.
 * The repository is cleaned up after loading unless configured otherwise.
 *
 * @param directorySkillDefinitionLoader the loader to use for parsing skills from directories
 * @param repositoryReferenceProvider the provider for cloning Git repositories
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
class GitHubSkillDefinitionLoader(
    private val directorySkillDefinitionLoader: DirectorySkillDefinitionLoader = DefaultDirectorySkillDefinitionLoader(),
    private val repositoryReferenceProvider: RepositoryReferenceProvider = RepositoryReferenceProvider.create(),
) {

    /**
     * Load all skills from a GitHub repository.
     *
     * @param owner the GitHub repository owner (user or organization)
     * @param repo the GitHub repository name
     * @param branch optional branch to clone (defaults to repository default branch)
     * @param skillsPath optional path within the repository where skills are located
     *                   (defaults to root of repository)
     * @return list of loaded skills
     * @throws SkillLoadException if the repository cannot be cloned or skills cannot be loaded
     */
    @JvmOverloads
    fun fromGitHub(
        owner: String,
        repo: String,
        branch: String? = null,
        skillsPath: String? = null,
    ): List<LoadedSkill> {
        val url = "https://github.com/$owner/$repo.git"

        val clonedRepo = try {
            repositoryReferenceProvider.cloneRepository(
                url = url,
                description = "Skills repository: $owner/$repo",
                branch = branch,
                depth = 1, // Shallow clone for efficiency
            )
        } catch (e: Exception) {
            throw SkillLoadException("Failed to clone GitHub repository: $owner/$repo", e)
        }

        return clonedRepo.use { repo ->
            val skillsDirectory = if (skillsPath != null) {
                repo.localPath.resolve(skillsPath)
            } else {
                repo.localPath
            }

            loadSkillsFromDirectory(skillsDirectory)
        }
    }

    /**
     * Load a single skill from a GitHub repository.
     *
     * @param owner the GitHub repository owner (user or organization)
     * @param repo the GitHub repository name
     * @param skillPath path to the skill directory within the repository
     * @param branch optional branch to clone (defaults to repository default branch)
     * @return the loaded skill
     * @throws SkillLoadException if the repository cannot be cloned or skill cannot be loaded
     */
    @JvmOverloads
    fun loadSkillFromGitHub(
        owner: String,
        repo: String,
        skillPath: String,
        branch: String? = null,
    ): LoadedSkill {
        val url = "https://github.com/$owner/$repo.git"

        val clonedRepo = try {
            repositoryReferenceProvider.cloneRepository(
                url = url,
                description = "Skill repository: $owner/$repo",
                branch = branch,
                depth = 1,
            )
        } catch (e: Exception) {
            throw SkillLoadException("Failed to clone GitHub repository: $owner/$repo", e)
        }

        return clonedRepo.use { repo ->
            val skillDirectory = repo.localPath.resolve(skillPath)
            directorySkillDefinitionLoader.load(skillDirectory)
        }
    }

    /**
     * Load skills from a Git URL (not just GitHub).
     *
     * @param url the Git repository URL
     * @param branch optional branch to clone
     * @param skillsPath optional path within the repository where skills are located
     * @return list of loaded skills
     */
    @JvmOverloads
    fun fromGitUrl(
        url: String,
        branch: String? = null,
        skillsPath: String? = null,
    ): List<LoadedSkill> {
        val clonedRepo = try {
            repositoryReferenceProvider.cloneRepository(
                url = url,
                description = "Skills repository: $url",
                branch = branch,
                depth = 1,
            )
        } catch (e: Exception) {
            throw SkillLoadException("Failed to clone Git repository: $url", e)
        }

        return clonedRepo.use { repo ->
            val skillsDirectory = if (skillsPath != null) {
                repo.localPath.resolve(skillsPath)
            } else {
                repo.localPath
            }

            loadSkillsFromDirectory(skillsDirectory)
        }
    }

    private fun loadSkillsFromDirectory(directory: Path): List<LoadedSkill> {
        // Check if the directory itself is a skill (has SKILL.md)
        val hasSkillMd = directory.toFile().listFiles()
            ?.any { it.name.equals("SKILL.md", ignoreCase = true) } == true

        return if (hasSkillMd) {
            // Single skill at root
            listOf(directorySkillDefinitionLoader.load(directory))
        } else {
            // Multiple skills in subdirectories
            directorySkillDefinitionLoader.loadAll(directory)
        }
    }

    companion object {

        /**
         * Create a new GitHubSkillDefinitionLoader with default settings.
         */
        @JvmStatic
        fun create(): GitHubSkillDefinitionLoader = GitHubSkillDefinitionLoader()
    }
}
