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
package com.embabel.agent.skills.support

import com.embabel.agent.skills.SkillFrontMatterFormatter
import com.embabel.agent.skills.spec.SkillMetadata

/**
 * Formats skill metadata in Claude's recommended XML format.
 *
 * Output format:
 * ```xml
 * <available_skills>
 *   <skill>
 *     <name>skill-name</name>
 *     <description>What it does</description>
 *   </skill>
 * </available_skills>
 * ```
 *
 * @see <a href="https://agentskills.io/integrate-skills">Agent Skills Integration</a>
 */
object ClaudeFrontMatterFormatter : SkillFrontMatterFormatter {

    override fun format(skills: List<SkillMetadata>): String {
        if (skills.isEmpty()) {
            return "<available_skills>\n</available_skills>"
        }

        val skillsXml = skills.joinToString("\n") { skill ->
            formatSkill(skill).prependIndent("  ")
        }

        return """
            |<available_skills>
            |$skillsXml
            |</available_skills>
        """.trimMargin()
    }

    override fun formatSkill(skill: SkillMetadata): String {
        return """
            |<skill>
            |  <name>${escapeXml(skill.name)}</name>
            |  <description>${escapeXml(skill.description)}</description>
            |</skill>
        """.trimMargin()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

}
