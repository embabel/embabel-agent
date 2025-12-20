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

import com.embabel.agent.skills.spec.SkillDefinition
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Default implementation of [DirectorySkillDefinitionLoader].
 *
 * Loads skills from directories according to the Agent Skills specification:
 * - Each skill is a directory containing a SKILL.md file
 * - SKILL.md contains YAML frontmatter (between --- delimiters) followed by markdown instructions
 * - Optional subdirectories: scripts/, references/, assets/
 *
 * @param validateFileReferences if true, validates that files referenced in instructions exist
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
class DefaultDirectorySkillDefinitionLoader(
    private val validateFileReferences: Boolean = true,
) : DirectorySkillDefinitionLoader {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override fun load(directory: Path): LoadedSkill {
        if (!Files.isDirectory(directory)) {
            throw SkillLoadException("Path is not a directory: $directory")
        }

        val skillFile = findSkillFile(directory)
            ?: throw SkillLoadException("No SKILL.md file found in directory: $directory")

        val content = Files.readString(skillFile)
        val (frontmatter, instructions) = parseFrontmatterAndBody(content, skillFile)

        return try {
            val skillDefinition = yamlMapper.readValue(frontmatter, SkillDefinition::class.java)
            val withInstructions = skillDefinition.copy(
                instructions = instructions.takeIf { it.isNotBlank() },
            )
            val loadedSkill = LoadedSkill(
                skillMetadata = withInstructions,
                basePath = directory.toAbsolutePath(),
            )

            if (loadedSkill.hasScripts()) {
                logger.warn(
                    "Skill '{}' contains scripts, but script execution is not yet supported",
                    loadedSkill.name
                )
            }

            if (validateFileReferences) {
                val validationResult = loadedSkill.validateFileReferences()
                if (!validationResult.isValid) {
                    throw SkillLoadException(
                        "Skill '${loadedSkill.name}' references missing files: ${validationResult.missingFiles.joinToString(", ")}"
                    )
                }
            }

            loadedSkill
        } catch (e: SkillLoadException) {
            throw e
        } catch (e: Exception) {
            throw SkillLoadException("Failed to parse SKILL.md in directory: $directory", e)
        }
    }

    override fun loadAll(parentDirectory: Path): List<LoadedSkill> {
        if (!Files.isDirectory(parentDirectory)) {
            throw SkillLoadException("Path is not a directory: $parentDirectory")
        }

        return Files.list(parentDirectory)
            .filter { Files.isDirectory(it) }
            .filter { findSkillFile(it) != null }
            .map { load(it) }
            .toList()
    }

    private fun findSkillFile(directory: Path): Path? {
        // SKILL.md is case-insensitive per the spec
        return Files.list(directory)
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().equals(SKILL_FILE_NAME, ignoreCase = true) }
            .findFirst()
            .orElse(null)
    }

    private fun parseFrontmatterAndBody(
        content: String,
        file: Path,
    ): Pair<String, String> {
        val lines = content.lines()

        if (lines.isEmpty() || lines[0].trim() != FRONTMATTER_DELIMITER) {
            throw SkillLoadException("SKILL.md must start with YAML frontmatter (---): $file")
        }

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (endIndex == -1) {
            throw SkillLoadException("YAML frontmatter not closed (missing ---): $file")
        }

        val frontmatter = lines.subList(1, endIndex + 1).joinToString("\n")
        val body = lines.drop(endIndex + 2).joinToString("\n").trim()

        return frontmatter to body
    }

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val FRONTMATTER_DELIMITER = "---"
    }
}
