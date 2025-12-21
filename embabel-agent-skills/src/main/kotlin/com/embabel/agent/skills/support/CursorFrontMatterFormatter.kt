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

import com.embabel.agent.skills.SkillFrontMatterFormatter
import com.embabel.agent.skills.spec.SkillMetadata

/**
 * Formats skill metadata in Cursor's preferred markdown format.
 *
 * Output format:
 * ```markdown
 * ## Available Skills
 *
 * - **skill-name**: What it does
 * - **another-skill**: Description of this skill
 * ```
 *
 * @see <a href="https://agentskills.io/integrate-skills">Agent Skills Integration</a>
 */
object CursorFrontMatterFormatter : SkillFrontMatterFormatter {

    override fun format(skills: List<SkillMetadata>): String {
        if (skills.isEmpty()) {
            return "## Available Skills\n\nNo skills available."
        }

        val skillsList = skills.joinToString("\n") { skill ->
            formatSkill(skill)
        }

        return """
            |## Available Skills
            |
            |$skillsList
        """.trimMargin()
    }

    override fun formatSkill(skill: SkillMetadata): String {
        return "- **${escapeMarkdown(skill.name)}**: ${escapeMarkdown(skill.description)}"
    }

    private fun escapeMarkdown(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("`", "\\`")
            .replace("[", "\\[")
            .replace("]", "\\]")
    }
}
