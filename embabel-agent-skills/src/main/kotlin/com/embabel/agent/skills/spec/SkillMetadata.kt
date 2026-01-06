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
package com.embabel.agent.skills.spec

/**
 * Metadata for an Agent Skill as defined by the YAML frontmatter of a SKILL.md file.
 *
 * This interface represents the minimal information needed to identify and select a skill.
 * It is used for system prompt inclusion where only name and description are needed
 * (~50-100 tokens per skill).
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
interface SkillMetadata {

    /**
     * The skill name. Required.
     * 1-64 characters, lowercase alphanumeric and hyphens only.
     * Cannot start/end with hyphen or contain consecutive hyphens.
     * Must match parent directory name.
     */
    val name: String

    /**
     * Description of what the skill does and when to use it. Required.
     * 1-1024 characters.
     * Should include keywords for agent task identification.
     */
    val description: String

    /**
     * The skill's license. Optional.
     * Can reference a bundled license file.
     */
    val license: String?

    /**
     * Environment requirements: product, system packages, network access. Optional.
     * 1-500 characters if provided.
     * Include only if specific requirements exist.
     */
    val compatibility: String?

    /**
     * Additional properties beyond the spec. Optional.
     * Key-value string mapping.
     * Recommendation: use uniquely-named keys to avoid conflicts.
     */
    val metadata: Map<String, String>?

    /**
     * Space-delimited list of pre-approved tools. Optional. Experimental.
     * Format example: "Bash(git:*) Bash(jq:*) Read"
     */
    val allowedTools: String?

    /**
     * The Markdown body content (instructions) after the YAML frontmatter.
     * Not part of the YAML frontmatter itself, but populated after parsing.
     */
    val instructions: String?
}
