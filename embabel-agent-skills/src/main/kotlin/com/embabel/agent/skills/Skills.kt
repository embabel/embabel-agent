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
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.api.tool.progressive.buildUnfoldedMessage
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
 *
 * ## Two consumer surfaces
 *
 * `Skills` implements both [LlmReference] and [UnfoldingTool]. Pick the surface
 * based on whether you want the LLM to see each loaded skill as its own
 * top-level tool, or one consolidated entry point with the catalog deferred:
 *
 * ### Per-skill (recommended for typical N): use [asIndividualReferences]
 *
 * Returns one [LlmReference] per loaded skill. Each one shows up in the LLM's
 * tool catalog with **its own name and description visible up front**, so the
 * LLM can pick the right skill in one shot. Catalog cost: O(N) lines in the
 * system prompt — fine for small/medium N.
 *
 * ### Consolidated (single skills entry point): use this instance directly as a [Tool]
 *
 * `Skills` implementing [UnfoldingTool] gives you **one top-level tool** in the
 * catalog (carrying just `name` + the container's generic `description`). The
 * names and descriptions of individual loaded skills are NOT visible until the
 * LLM invokes this tool — they are delivered via [childToolUsageNotes] in the
 * unfold response.
 *
 * **Only use this form when you genuinely want a single consolidated skills
 * tool.** It costs the LLM an extra round-trip (unfold to discover the catalog,
 * then call activate) and removes per-skill discoverability from the catalog.
 * Pick it when N is large enough that listing every skill in the system prompt
 * would dominate it, or when skills are a peripheral capability you don't want
 * cluttering the top-level tool list. Otherwise prefer [asIndividualReferences].
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
) : LlmReference, UnfoldingTool {

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

    // ---- UnfoldingTool surface ----------------------------------------------
    //
    // Consuming `Skills` as a single [UnfoldingTool] gives one consolidated
    // top-level tool whose `description` is generic. The catalog of loaded
    // skills (name + description per skill) is delivered via
    // [childToolUsageNotes] only AFTER the LLM invokes this tool — i.e. it is
    // hidden from the system prompt's tool catalog. See class KDoc for when
    // to use this form vs. [asIndividualReferences].

    override val definition: Tool.Definition = Tool.Definition(
        name = name,
        description = description,
        inputSchema = Tool.InputSchema.empty(),
    )

    /**
     * Inner tools revealed when this `Skills` is invoked as an [UnfoldingTool]:
     * the same tools surfaced via [tools] (activate, listResources, readResource,
     * plus every loaded skill's script tools).
     */
    override val innerTools: List<Tool> get() = tools()

    /**
     * Catalog of every loaded skill (name + description), formatted via
     * [frontMatterFormatter]. Delivered as the progressively-disclosed payload
     * of the unfold response so the LLM can choose which skill to activate.
     * Null when no skills are loaded.
     */
    override val childToolUsageNotes: String?
        get() = if (skills.isEmpty()) null else """
            Available skills:

            ${frontMatterFormatter.format(skills)}

            Call activate(name=…) with the skill's name to load its full
            instructions. Use listResources and readResource for bundled files.
        """.trimIndent()

    override fun call(input: String): Tool.Result =
        Tool.Result.text(buildUnfoldedMessage(innerTools, childToolUsageNotes))

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
     * Return each loaded skill as its own [LlmReference], each wrapping
     * with [withUnfolding] so the LLM sees one top-level tool per skill
     * that unfolds into that skill's tools (activate, listResources, readResource, scripts).
     *
     * This gives the LLM a clear per-skill entry point instead of a single
     * monolithic "skills" tool containing everything.
     *
     * The returned references share this [Skills] instance for tool execution
     * (activate, listResources, readResource) so all state is consistent.
     */
    fun asIndividualReferences(): List<LlmReference> {
        if (skills.isEmpty()) return emptyList()
        return skills.map { skill ->
            val perSkillTools = buildList {
                // The shared activate/listResources/readResource tools (bound to this Skills instance)
                addAll(Tool.fromInstance(this@Skills))
                // Script tools specific to this skill
                addAll(skill.getScriptTools(scriptExecutionEngine))
            }
            LlmReference.of(
                name = skill.name,
                description = skill.description,
                tools = perSkillTools,
                notes = """
                    Skill: ${skill.name}
                    ${skill.description}

                    To use this skill, call activate with name "${skill.name}" to get full instructions.
                    Use listResources and readResource to access the skill's bundled resources.
                """.trimIndent(),
            ).withUnfolding()
        }
    }

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
