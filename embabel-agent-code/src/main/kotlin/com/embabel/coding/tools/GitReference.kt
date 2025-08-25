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
package com.embabel.coding.tools

import com.embabel.agent.tools.file.DefaultFileReadLog
import com.embabel.agent.tools.file.FileReadLog
import com.embabel.agent.tools.file.FileReadTools
import com.embabel.agent.tools.file.WellKnownFileContentTransformers
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.util.StringTransformer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * Limits for file formats when reading files from a cloned repository.
 * @param fileCountLimit Limit of the number of files that will allow a PromptContributor to be returned.
 * @param fileSizeLimit Limit of the size of file that will be included in the prompt contribution.
 */
data class FileFormatLimits(
    val fileCountLimit: Int = 200,
    val fileSizeLimit: Long = 200_000,
)

/**
 * Reference to a cloned Git repository with automatic cleanup capabilities.
 * Exposes LLM tools.
 * @param localPath The local path where the repository is cloned.
 * @param shouldDeleteOnClose If true, the repository will be deleted when closed.

 */
data class ClonedRepository(
    val localPath: Path,
    internal val shouldDeleteOnClose: Boolean = true,
    val fileFormatLimits: FileFormatLimits = FileFormatLimits(),
) : AutoCloseable, FileReadTools, FileReadLog by DefaultFileReadLog() {

    override val fileContentTransformers: List<StringTransformer>
        get() = listOf(WellKnownFileContentTransformers.removeApacheLicenseHeader)

    override val root = localPath.toAbsolutePath().toString()

    override fun close() {
        if (shouldDeleteOnClose && Files.exists(localPath)) {
            try {
                localPath.toFile().deleteRecursively()
            } catch (e: Exception) {
                // Log warning but don't fail
                System.err.println("Warning: Could not delete temporary git repository at $localPath: ${e.message}")
            }
        }
    }

    /**
     * Write all files in the repository to a single string.
     * Only use this for small repositories as it loads everything into memory.
     * Applies file content transformers (like removing Apache license headers).
     * Uses FileVisitor for cross-platform compatibility.
     */
    fun writeAllFilesToString(): String {
        val result = StringBuilder()
        val files = mutableListOf<Path>()

        try {
            // Use FileVisitor for cross-platform .git exclusion
            Files.walkFileTree(localPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dirName = dir.fileName?.toString()
                    return if (dirName == ".git") {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        files.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // Log but continue processing other files
                    return FileVisitResult.CONTINUE
                }
            })

            // Sort files and process them
            files.sorted().forEach { path ->
                val relativePath = localPath.relativize(path)
                val content = try {
                    if (Files.size(path) > fileFormatLimits.fileSizeLimit) {
                        "// File too large to read: $relativePath"
                    } else {
                        val rawContent = Files.readString(path)
                        WellKnownFileContentTransformers.removeApacheLicenseHeader.transform(rawContent)
                    }
                } catch (e: Exception) {
                    "// Error reading file: ${e.message}"
                }

                result.append("=== $relativePath ===\n")
                result.append(content)
                result.append("\n\n")
            }
        } catch (e: Exception) {
            result.append("Error walking repository: ${e.message}")
        }

        return result.toString()
    }

    /**
     * Return a prompt contributor if the repo is small enough
     */
    fun promptContributor(): PromptContributor? {
        if (fileCount() > 1000 || writeAllFilesToString().length > fileFormatLimits.fileSizeLimit) {
            return null // Too large to contribute meaningfully
        }
        return object : PromptContributor {
            override fun contribution(): String {
                return """
                    |Cloned repository at: $localPath
                    |Use file tools to read files in the repository.
                    |Read limit is set to ${fileFormatLimits.fileSizeLimit} bytes.
                """.trimMargin()
            }
        }
    }

}

class GitReference(
    private val fileFormatLimits: FileFormatLimits = FileFormatLimits(),
) {

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
    fun cloneRepository(
        url: String,
        branch: String? = null,
        depth: Int? = null,
    ): ClonedRepository {
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

            return ClonedRepository(
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
    ): ClonedRepository {
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

        return ClonedRepository(
            localPath = targetDirectory,
            shouldDeleteOnClose = false,
            fileFormatLimits = fileFormatLimits,
        )
    }

    private fun createTempDirectory(): Path {
        return Files.createTempDirectory("embabel-git-")
    }
}
