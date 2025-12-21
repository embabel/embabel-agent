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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.skills.support.*
import org.slf4j.LoggerFactory

fun interface SkillFilter {

    fun accept(skill: LoadedSkill): Boolean

    companion object {

        val WITHOUT_SCRIPTS = SkillFilter { skill ->
            skill.listResources(ResourceType.SCRIPTS).isEmpty()
        }

    }
}

/**
 * Programming model for bringing Agent Skills into a PromptRunner.
 *
 * See the [Agent Skills Specification](https://agentskills.io/specification)
 * for the layout of skills.
 */
data class Skills @JvmOverloads constructor(
    override val name: String,
    override val description: String,
    val skills: List<LoadedSkill> = emptyList(),
    private val directorySkillDefinitionLoader: DirectorySkillDefinitionLoader = DefaultDirectorySkillDefinitionLoader(),
    private val gitHubSkillDefinitionLoader: GitHubSkillDefinitionLoader = GitHubSkillDefinitionLoader(
        directorySkillDefinitionLoader,
    ),
    private val frontMatterFormatter: SkillFrontMatterFormatter = ClaudeFrontMatterFormatter,
    private val filter: SkillFilter = SkillFilter { true },
) : LlmReference {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun withFrontMatterFormatter(formatter: SkillFrontMatterFormatter): Skills {
        return copy(frontMatterFormatter = formatter)
    }

    fun withFilter(filter: SkillFilter): Skills {
        return copy(filter = filter)
    }

    fun withSkills(vararg loadedSkills: LoadedSkill): Skills {
        logger.info("Added ${loadedSkills.size} skills")
        return copy(skills = skills + loadedSkills)
    }

    /**
     * Load a skill from a local directory.
     */
    fun withLocalSkill(directory: String): Skills {
        val loadedSkill = directorySkillDefinitionLoader.load(directory)
        logger.info("Loaded skill from local path $directory")
        return copy(skills = skills + loadedSkill)
    }

    /**
     * Load skills from a local parent directory.
     */
    fun withLocalSkills(parentDirectory: String): Skills {
        val loadedSkills = directorySkillDefinitionLoader.loadAll(parentDirectory)
        if (loadedSkills.isEmpty()) {
            logger.warn("No skills found in local path $parentDirectory")
        } else {
            logger.info("Loaded ${loadedSkills.size} skills from local path $parentDirectory")
        }
        return copy(skills = skills + loadedSkills)
    }

    /**
     * Load skills from a GitHub repository.
     * @param owner the GitHub repository owner (user or organization)
     * @param repo the GitHub repository name
     * @param skillsPath optional path within the repository where skills are located
     * (defaults to root of repository)
     * @param branch optional branch to clone (defaults to repository default branch)
     */
    @JvmOverloads
    fun withGitHubSkills(
        owner: String,
        repo: String,
        skillsPath: String? = null,
        branch: String? = null,
    ): Skills {
        val loadedSkills = gitHubSkillDefinitionLoader.fromGitHub(
            owner = owner,
            repo = repo,
            branch = branch,
            skillsPath = skillsPath,
        )
        if (loadedSkills.isEmpty()) {
            logger.warn("No skills found in GitHub repository $owner/$repo")
        } else {
            logger.info("Loaded ${loadedSkills.size} skills from GitHub repository $owner/$repo")
        }
        return copy(skills = skills + loadedSkills)
    }

    /**
     * Load skills from a GitHub URL.
     *
     * Parses URLs in the following formats:
     * - `https://github.com/owner/repo`
     * - `https://github.com/owner/repo/tree/branch`
     * - `https://github.com/owner/repo/tree/branch/path/to/skills`
     * - `https://github.com/owner/repo/blob/branch/path/to/skill`
     *
     * @param url the GitHub URL to load skills from
     */
    fun withGitHubUrl(url: String): Skills {
        val loadedSkills = gitHubSkillDefinitionLoader.fromGitHubUrl(url)
        if (loadedSkills.isEmpty()) {
            logger.warn("No skills found at GitHub URL: $url")
        } else {
            logger.info("Loaded ${loadedSkills.size} skills from GitHub URL: $url")
        }
        return copy(skills = skills + loadedSkills)
    }

    private val tools: List<Tool> = buildList { }

    override fun tools(): List<Tool> = tools

    override fun toolInstances() = listOf(this)

    override fun notes(): String {
        return """
            The agent has access to the following skills:

            ${frontMatterFormatter.format(skills)}

            Use these skills to assist in completing the user's request.
        """.trimIndent()
    }

    /**
     * Activate a skill by name, returning its full instructions.
     * This is the "lazy loading" mechanism - minimal metadata is shown in the system prompt,
     * but full instructions are only loaded when the skill is activated.
     */
    @LlmTool(description = "Activate a skill by name to get its full instructions. Use this when you need to perform a task that matches a skill's description.")
    fun activate(
        @LlmTool.Param("name of the skill to activate") name: String,
    ): String {
        val skill = findSkill(name)
            ?: return "Skill not found: '$name'. Available skills: ${skills.map { it.name }}"

        return skill.getActivationText()
    }

    /**
     * List available resources for a skill.
     */
    @LlmTool(description = "List available resources (scripts, references, or assets) for a skill")
    fun listResources(
        @LlmTool.Param("name of the skill") skillName: String,
        @LlmTool.Param("type of resources: 'scripts', 'references', or 'assets'") resourceType: String,
    ): String {
        val skill = findSkill(skillName)
            ?: return "Skill not found: '$skillName'"

        val type = ResourceType.fromString(resourceType)
            ?: return "Invalid resource type: '$resourceType'. Must be one of: scripts, references, assets"

        val files = skill.listResources(type)
        if (files.isEmpty()) {
            return "No $resourceType found for skill '$skillName'"
        }

        return "Files in $resourceType for '$skillName':\n${files.joinToString("\n") { "- $it" }}"
    }

    /**
     * Read a resource file from a skill.
     */
    @LlmTool(description = "Read a resource file (script, reference, or asset) from a skill")
    fun readResource(
        @LlmTool.Param("name of the skill") skillName: String,
        @LlmTool.Param("type of resource: 'scripts', 'references', or 'assets'") resourceType: String,
        @LlmTool.Param("name of the file to read") fileName: String,
    ): String {
        val skill = findSkill(skillName)
            ?: return "Skill not found: '$skillName'"

        val type = ResourceType.fromString(resourceType)
            ?: return "Invalid resource type: '$resourceType'. Must be one of: scripts, references, assets"

        return skill.readResource(type, fileName)
            ?: "File not found: '$fileName' in $resourceType for skill '$skillName'"
    }

    private fun findSkill(name: String): LoadedSkill? {
        return skills.find { it.name.equals(name, ignoreCase = true) }
    }
}
