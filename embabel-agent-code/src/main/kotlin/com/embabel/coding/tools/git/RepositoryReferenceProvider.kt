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
package com.embabel.coding.tools.git

import com.embabel.agent.core.AgentProcess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.springframework.ai.tool.annotation.Tool
import java.nio.file.Files
import java.nio.file.Path

/**
 * Limits for file formats when reading files from a cloned repository.
 * @param fileCountLimit Limit of the number of files that will allow a PromptContributor to be returned.
 * @param fileSizeLimit Limit of the size of file that will be included in the prompt contribution.
 */
data class FileFormatLimits(
    val fileCountLimit: Int = 200,
    val fileSizeLimit: Long = 200_000,
)

data class RepositoryReferenceProvider(
    private val fileFormatLimits: FileFormatLimits = FileFormatLimits(),
) {

    fun withFileCountLimit(limit: Int): RepositoryReferenceProvider {
        return copy(
            fileFormatLimits = this.fileFormatLimits.copy(fileCountLimit = limit),
        )
    }

    fun withFileSizeLimit(limit: Long): RepositoryReferenceProvider {
        return copy(
            fileFormatLimits = this.fileFormatLimits.copy(fileSizeLimit = limit),
        )
    }

    /**
     * Will add a reference to the blackboard
     */
    @Tool(description = "Clone a Git repository from the given URL to a temporary directory")
    fun clone(url: String): String {
        val agentProcess = AgentProcess.get()
        if (agentProcess == null) {
            error("No agent process: Cannot add cloned repository to agent process")
        } else {
            val clonedRepositoryReference = cloneRepository(url)
            agentProcess += clonedRepositoryReference
            return "Cloned repository from $url to ${clonedRepositoryReference.localPath} and added to agent process ${agentProcess.id}"
        }
    }

    /**
     * Clone a Git repository from the given URL to a temporary directory.
     * The returned object will expose tools.
     *
     * @param url The Git repository URL (supports both HTTP/HTTPS and SSH)
     * @param branch Optional specific branch to check out (defaults to repository default)
     * @param depth Optional shallow clone depth (null for full clone)
     * @return ClonedRepository with absolute path and cleanup capabilities
     * @throws GitAPIException if the clone operation fails
     */
    @JvmOverloads
    fun cloneRepository(
        url: String,
        branch: String? = null,
        depth: Int? = null,
    ): ClonedRepositoryReference {
        val tempDir = createTempDirectory()

        try {
            val cloneCommand = Git.cloneRepository()
                .setURI(url)
                .setDirectory(tempDir.toFile())
                .setCloneAllBranches(branch == null)

            branch?.let { cloneCommand.setBranch(it) }
            depth?.let { cloneCommand.setDepth(it) }

            cloneCommand.call().use { git ->
                // Verify clone was successful
                if (!Files.exists(tempDir.resolve(".git"))) {
                    throw IllegalStateException("Clone operation completed but .git directory not found")
                }
            }

            return ClonedRepositoryReference(
                url = url,
                localPath = tempDir,
                shouldDeleteOnClose = true,
                fileFormatLimits = fileFormatLimits,
            )
        } catch (e: Exception) {
            // Clean up on failure
            try {
                tempDir.toFile().deleteRecursively()
            } catch (cleanupEx: Exception) {
                e.addSuppressed(cleanupEx)
            }
            throw when (e) {
                is GitAPIException -> e
                else -> e
            }
        }
    }

    /**
     * Clone a repository to a specific directory (not temporary).
     * The returned ClonedRepository will not auto-delete on close.
     */
    fun cloneRepositoryTo(
        url: String,
        targetDirectory: Path,
        branch: String? = null,
        depth: Int? = null,
    ): ClonedRepositoryReference {
        if (Files.exists(targetDirectory) && Files.list(targetDirectory).use { it.findFirst().isPresent }) {
            throw IllegalArgumentException("Target directory $targetDirectory already exists and is not empty")
        }

        Files.createDirectories(targetDirectory.parent)

        val cloneCommand = Git.cloneRepository()
            .setURI(url)
            .setDirectory(targetDirectory.toFile())
            .setCloneAllBranches(branch == null)

        branch?.let { cloneCommand.setBranch(it) }
        depth?.let { cloneCommand.setDepth(it) }

        cloneCommand.call().use { git ->
            // Verify clone was successful
            if (!Files.exists(targetDirectory.resolve(".git"))) {
                throw IllegalStateException("Clone operation completed but .git directory not found")
            }
        }

        return ClonedRepositoryReference(
            url = url,
            localPath = targetDirectory,
            shouldDeleteOnClose = false,
            fileFormatLimits = fileFormatLimits,
        )
    }

    private fun createTempDirectory(): Path {
        return Files.createTempDirectory("embabel-git-")
    }

    companion object {

        @JvmStatic
        fun create(): RepositoryReferenceProvider = RepositoryReferenceProvider()

    }
}
