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

import com.embabel.agent.skills.spec.SkillMetadata

import java.nio.file.Files
import java.nio.file.Path

/**
 * Types of bundled resources available in a skill directory.
 */
enum class ResourceType(val directoryName: String) {
    SCRIPTS("scripts"),
    REFERENCES("references"),
    ASSETS("assets");

    companion object {
        /**
         * Parse a resource type from a string, case-insensitive.
         *
         * @return the matching ResourceType, or null if not found
         */
        fun fromString(value: String): ResourceType? {
            return entries.find { it.directoryName.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * A skill that has been loaded from a directory, with access to bundled resources.
 *
 * This extends [SkillMetadata] with the ability to access scripts, references, and assets
 * from the skill's directory structure.
 *
 * @param skillMetadata the parsed skill metadata
 * @param basePath the directory path where this skill was loaded from
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
data class LoadedSkill(
    private val skillMetadata: SkillMetadata,
    val basePath: Path,
    private val fileReferenceExtractor: InstructionFileReferenceExtractor = InstructionFileReferenceExtractor,
) : SkillMetadata by skillMetadata {

    /**
     * Check if this skill has a scripts directory.
     */
    fun hasScripts(): Boolean = hasResourceType(ResourceType.SCRIPTS)

    /**
     * Check if this skill has a references directory.
     */
    fun hasReferences(): Boolean = hasResourceType(ResourceType.REFERENCES)

    /**
     * Check if this skill has an assets directory.
     */
    fun hasAssets(): Boolean = hasResourceType(ResourceType.ASSETS)

    /**
     * Check if this skill has the specified resource type directory.
     */
    fun hasResourceType(resourceType: ResourceType): Boolean =
        Files.isDirectory(basePath.resolve(resourceType.directoryName))

    /**
     * List files in a resource directory.
     *
     * @param resourceType the type of resource to list
     * @return list of file names, or empty list if directory doesn't exist
     */
    fun listResources(resourceType: ResourceType): List<String> {
        val resourcePath = basePath.resolve(resourceType.directoryName)

        if (!Files.isDirectory(resourcePath)) {
            return emptyList()
        }

        return Files.list(resourcePath)
            .filter { Files.isRegularFile(it) }
            .map { it.fileName.toString() }
            .toList()
            .sorted()
    }

    /**
     * Read a resource file.
     *
     * @param resourceType the type of resource
     * @param fileName the name of the file to read
     * @return the file contents, or null if not found or path traversal detected
     */
    fun readResource(resourceType: ResourceType, fileName: String): String? {
        val resourceDir = resourceType.directoryName
        val filePath = basePath.resolve(resourceDir).resolve(fileName)

        // Security: ensure the file is within the expected directory (no path traversal)
        val normalizedPath = filePath.normalize().toAbsolutePath()
        val expectedParent = basePath.resolve(resourceDir).normalize().toAbsolutePath()
        if (!normalizedPath.startsWith(expectedParent)) {
            return null
        }

        if (!Files.isRegularFile(filePath)) {
            return null
        }

        return try {
            Files.readString(filePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get the full activation text for this skill, including instructions and resource hints.
     */
    fun getActivationText(): String {
        val skillInstructions = instructions
        return buildString {
            appendLine("# Skill: $name")
            appendLine()
            if (skillInstructions != null) {
                appendLine(skillInstructions)
            } else {
                appendLine("No instructions available for this skill.")
            }

            if (hasScripts() || hasReferences() || hasAssets()) {
                appendLine()
                appendLine("## Available Resources")
                ResourceType.entries.filter { hasResourceType(it) }.forEach { type ->
                    appendLine("- ${type.name.lowercase().replaceFirstChar { it.uppercase() }}: use `listResources` and `readResource` with type '${type.directoryName}'")
                }
            }
        }.trim()
    }

    /**
     * Validate that all file references in the instructions exist.
     *
     * @return validation result with any missing file errors
     */
    fun validateFileReferences(): FileReferenceValidationResult {
        val references = fileReferenceExtractor.extract(instructions)
        val missingFiles = mutableListOf<String>()

        for (reference in references) {
            val filePath = basePath.resolve(reference)

            // Security: ensure the file is within the skill directory (no path traversal)
            val normalizedPath = filePath.normalize().toAbsolutePath()
            val expectedParent = basePath.normalize().toAbsolutePath()

            if (!normalizedPath.startsWith(expectedParent)) {
                missingFiles.add("$reference (invalid path)")
                continue
            }

            if (!Files.exists(filePath)) {
                missingFiles.add(reference)
            }
        }

        return if (missingFiles.isEmpty()) {
            FileReferenceValidationResult.VALID
        } else {
            FileReferenceValidationResult(
                isValid = false,
                missingFiles = missingFiles,
            )
        }
    }

}

/**
 * Result of validating file references in skill instructions.
 */
data class FileReferenceValidationResult(
    val isValid: Boolean,
    val missingFiles: List<String>,
) {
    companion object {
        val VALID = FileReferenceValidationResult(isValid = true, missingFiles = emptyList())
    }
}
