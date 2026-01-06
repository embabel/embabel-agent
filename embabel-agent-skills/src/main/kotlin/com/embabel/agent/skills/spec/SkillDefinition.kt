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

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data class for parsing the YAML frontmatter of a SKILL.md file.
 * Implements [SkillMetadata] for use in system prompts.
 *
 * This is primarily used internally by the loader for YAML deserialization.
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
data class SkillDefinition(
    override val name: String,
    override val description: String,
    override val license: String? = null,
    override val compatibility: String? = null,
    override val metadata: Map<String, String>? = null,
    @field:JsonProperty("allowed-tools")
    override val allowedTools: String? = null,
    override val instructions: String? = null,
) : SkillMetadata
