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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.skills.script.NoOpExecutionEngine
import com.embabel.agent.skills.script.SkillScriptExecutionEngine
import com.embabel.agent.skills.support.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
    private val scriptExecutionEngine: SkillScriptExecutionEngine = NoOpExecutionEngine,
) : LlmReference {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Tracks which skills have been activated. Script tools are only available
     * for activated skills (lazy loading pattern).
     */
    private val activatedSkillNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun withFrontMatterFormatter(formatter: SkillFrontMatterFormatter): Skills {
        return copy(frontMatterFormatter = formatter)
    }

    fun withFilter(filter: SkillFilter): Skills {
        return copy(filter = filter)
    }

    /**
     * Set the script execution engine for running skill scripts.
     * Script tools will only be available for activated skills.
     */
    fun withScriptExecutionEngine(engine: SkillScriptExecutionEngine): Skills {
        return copy(scriptExecutionEngine = engine)
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

    override fun tools(): List<Tool> {
        val annotationTools = Tool.fromInstance(this)

        // Include script tools for ALL skills (not just activated ones).
        // This is necessary because PromptRunner captures the tool list at startup
        // and doesn't refresh it after activate() is called.
        // The ScriptTool will still work - activation is only needed for instructions.
        val scriptTools = skills
            .flatMap { skill -> skill.getScriptTools(scriptExecutionEngine) }

        return annotationTools + scriptTools
    }

    override fun notes(): String {
        return """
            The agent has access to the following skills:

            ${frontMatterFormatter.format(skills)}

            Use these skills to assist in completing the user's request.
            You use a particular skill by calling the "activate" tool with the skill's name
            as parameter. You can also load skill resources (scripts, references, or assets)
            using the "listResources" and "readResource" tools.
        """.trimIndent()
    }

    /**
     * Activate a skill by name, returning its full instructions.
     * This is the "lazy loading" mechanism - minimal metadata is shown in the system prompt,
     * but full instructions are only loaded when the skill is activated.
     *
     * Script tools for all skills are always available via [tools] - activation is only
     * needed to load full instructions and resource information.
     */
    @LlmTool(description = "Activate a skill by name to get its full instructions and learn about available script tools. Use this when you need to perform a task that matches a skill's description.")
    fun activate(
        @LlmTool.Param("name of the skill to activate") name: String,
    ): String {
        val skill = findSkill(name)
            ?: return "Skill not found: '$name'. Available skills: ${skills.map { it.name }}"

        // Track activation for potential future use
        activatedSkillNames.add(skill.name)

        val scriptTools = skill.getScriptTools(scriptExecutionEngine)
        val activationText = skill.getActivationText()

        return if (scriptTools.isNotEmpty()) {
            val toolNames = scriptTools.map { it.definition.name }
            """
            |$activationText
            |
            |## Available Script Tools
            |The following script tools can be used for this skill:
            |${toolNames.joinToString("\n") { "- $it" }}
            """.trimMargin()
        } else {
            activationText
        }
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

    /**
     * Return each loaded skill as its own [LlmReference] PLUS one shared
     * reference for the bundled-resource helpers (`listResources` /
     * `readResource`). The LLM sees one top-level tool per skill in its
     * catalog — a [SkillActivationTool] whose name and description match
     * the skill — and invoking it returns the skill body directly as the
     * tool result.
     *
     * This is a **plain Tool**, not an [com.embabel.agent.api.tool.progressive.UnfoldingTool]:
     * no preamble, no inner-tool swap, no removal-from-catalog after
     * invocation. Why: the unfolding pattern's "Tools now available: X. You
     * MUST call one of these tools." preamble fits a tool-grouping facade,
     * but a skill's operative path is `execute_javascript` (already in the
     * catalog at the top level). The unfold preamble was misdirecting
     * models into calling auxiliary tools (`listResources`/`readResource`)
     * instead of following the body's guidance to call `execute_*`. Removed
     * the unfold layer; the body is now the lead, not a footnote.
     *
     * Activation cost: 1 round-trip — the LLM calls the per-skill tool,
     * gets the body, and proceeds. The tool persists in the catalog so
     * re-activation is a no-op re-call rather than a "tool no longer
     * exists" surprise.
     */
    fun asIndividualReferences(): List<LlmReference> {
        if (skills.isEmpty()) return emptyList()

        val perSkill = skills.map { skill ->
            LlmReference.of(
                name = skill.name,
                description = skill.description,
                tools = listOf<Tool>(SkillActivationTool(skill)) +
                    skill.getScriptTools(scriptExecutionEngine),
                notes = "",
            )
        }

        // Single shared reference holding listResources / readResource —
        // both take the skill name as a parameter, so one set covers every
        // loaded skill and we avoid N copies of the same two tools in the
        // catalog. `activate` is excluded because per-skill activation is
        // now done via the per-skill tool above.
        val sharedResourceTools = Tool.fromInstance(this@Skills)
            .filter { it.definition.name != "activate" }
        val sharedRef = LlmReference.of(
            name = "skill_resources",
            description = "Inspect bundled files (references, scripts, assets) for any loaded skill.",
            tools = sharedResourceTools,
            notes = "",
        )

        return perSkill + sharedRef
    }

    /**
     * Plain [Tool] returned for each loaded skill. The tool's `name` and
     * `description` come from the skill's frontmatter — this is what the
     * LLM sees in its catalog. Calling the tool returns the skill body
     * (instructions + resource manifest + script-tool names) as the tool
     * result. No unfold, no preamble, no swap.
     */
    private inner class SkillActivationTool(private val skill: LoadedSkill) : Tool {

        override val definition = Tool.Definition(
            name = sanitizeToolName(skill.name),
            description = skill.description,
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            activatedSkillNames.add(skill.name)
            return Tool.Result.text(skillBody(skill))
        }
    }

    /**
     * Build the body returned when the LLM activates a skill. Mirrors what
     * [activate] returns, but as a pure function (no `activatedSkillNames`
     * side effect — that's tracked at the call site).
     */
    private fun skillBody(skill: LoadedSkill): String {
        val activationText = skill.getActivationText()
        val scriptTools = skill.getScriptTools(scriptExecutionEngine)
        if (scriptTools.isEmpty()) return activationText
        return """
            |$activationText
            |
            |## Available Script Tools
            |The following script tools can be used for this skill:
            |${scriptTools.joinToString("\n") { "- ${it.definition.name}" }}
        """.trimMargin()
    }

    /**
     * Lower-case + replace `-` with `_`. Mirrors the sanitization the
     * framework applies when wrapping a skill in an [com.embabel.agent.api.tool.progressive.UnfoldingTool]
     * via [LlmReference.toolPrefix], so existing prompts and acceptance
     * tests that reference e.g. `github_workflows` keep matching.
     */
    private fun sanitizeToolName(name: String): String =
        name.replace('-', '_').lowercase()

    /**
     * Match by canonical form: case-insensitive, hyphens and underscores
     * treated as equivalent. Tool surfaces commonly sanitize hyphens to
     * underscores when exposing skills as top-level tools (e.g. via
     * `withUnfolding`), so the model sees `github_workflows` for a skill
     * named `github-workflows` and naturally calls `activate` with the
     * sanitized form. Accepting either separator removes a wasted
     * iteration that small models in particular pay for.
     */
    private fun findSkill(name: String): LoadedSkill? {
        val target = canonicalize(name)
        return skills.find { canonicalize(it.name) == target }
    }

    private fun canonicalize(name: String): String =
        name.replace('_', '-').lowercase()
}
