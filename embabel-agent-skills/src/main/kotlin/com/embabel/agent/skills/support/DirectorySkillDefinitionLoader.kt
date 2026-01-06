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

import java.nio.file.Path

/**
 * Loader for Agent Skills from local directories according to the specification.
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
interface DirectorySkillDefinitionLoader {

    /**
     * Load a skill from a directory.
     *
     * @param directory the skill directory containing SKILL.md
     * @return the loaded skill with access to resources
     * @throws SkillLoadException if the skill cannot be loaded
     */
    fun load(directory: Path): LoadedSkill

    /**
     * Load a single skill from a directory path as a string.
     */
    fun load(skillsPath: String): LoadedSkill {
        return load(Path.of(skillsPath))
    }

    /**
     * Load all skills from a parent directory.
     * Each subdirectory containing a SKILL.md file is loaded as a skill.
     *
     * @param parentDirectory the parent directory containing skill directories
     * @return list of loaded skills
     */
    fun loadAll(parentDirectory: Path): List<LoadedSkill>

    /**
     * Load all skills from a parent directory path as a string.
     */
    fun loadAll(skillsPath: String): List<LoadedSkill> {
        return loadAll(Path.of(skillsPath))
    }
}

/**
 * Exception thrown when a skill cannot be loaded.
 */
class SkillLoadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
