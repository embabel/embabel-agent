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

import com.embabel.agent.skills.spec.SkillMetadata

/**
 * Formats skill metadata for inclusion in system prompts.
 *
 * Per the Agent Skills integration specification, skills should be listed
 * with minimal metadata (~50-100 tokens per skill) to keep
 * initial context usage low while enabling informed skill selection.
 *
 * @see <a href="https://agentskills.io/integrate-skills">Agent Skills Integration</a>
 */
interface SkillFrontMatterFormatter {

    /**
     * Format a list of skills as a system prompt fragment.
     *
     * @param skills the skills to format
     * @return formatted string for inclusion in system prompt
     */
    fun format(skills: List<SkillMetadata>): String

    /**
     * Format a single skill.
     *
     * @param skill the skill to format
     * @return formatted string for the skill
     */
    fun formatSkill(skill: SkillMetadata): String
}
