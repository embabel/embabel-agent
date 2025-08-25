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
package com.embabel.agent.tools.file

import com.embabel.agent.api.common.support.SelfToolCallbackPublisher
import com.embabel.agent.tools.DirectoryBased
import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * Read and Write file tools. Extend FileReadTools for safe read only use
 */
interface FileTools : FileReadTools, FileWriteTools {

    override fun getPathsAccessed(): List<String> = (getPathsRead() + getChanges().map { it.path }).distinct()

    companion object {

        /**
         * Create a FileReadTools instance with the given root directory.
         */
        @JvmStatic
        @JvmOverloads
        fun readOnly(
            root: String,
            fileContentTransformers: List<StringTransformer> = emptyList(),
        ): FileReadTools = DefaultFileReadTools(root, fileContentTransformers)

        /**
         * Create a readwrite FileTools instance with the given root directory.
         */
        @JvmStatic
        @JvmOverloads
        fun readWrite(
            root: String,
            fileContentTransformers: List<StringTransformer> = emptyList(),
        ): FileTools = DefaultFileTools(root, fileContentTransformers)
    }
}

private class DefaultFileReadTools(
    override val root: String,
    override val fileContentTransformers: List<StringTransformer> = emptyList(),
) : FileReadTools, FileReadLog by DefaultFileReadLog()


private class DefaultFileTools(
    override val root: String,
    override val fileContentTransformers: List<StringTransformer> = emptyList(),
) : FileTools, FileReadLog by DefaultFileReadLog(), FileChangeLog by DefaultFileChangeLog()


/**
 * LLM-ready ToolCallbacks and convenience methods for file operations.
 * Use at your own risk: This makes changes to your host machine!!
 */
interface FileReadTools : DirectoryBased, FileReadLog, FileAccessLog, SelfToolCallbackPublisher {

    /**
     * Provide sanitizers that run on file content before returning it.
     * They must be sure not to change any content that may need to be replaced
     * as this will break editing if editing is done in the same session.
     */
    val fileContentTransformers: List<StringTransformer>

    override fun getPathsAccessed(): List<String> = getPathsRead()

    /**
     * Does this file exist?
     */
    fun exists(): Boolean {
        return Files.exists(resolvePath(""))
    }

    /**
     * Count the total number of files in the repository (excluding .git directory).
     * Uses FileVisitor for cross-platform compatibility (Windows and Linux).
     */
    @Tool(description = "Count the number of files in the repository, excluding .git directory")
    fun fileCount(): Int {
        return try {
            val rootPath = resolvePath("")
            val fileCount = AtomicInteger(0)

            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dirName = dir.fileName?.toString()
                    return if (dirName == ".git") {
                        // Skip entire .git directory and all its contents
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Only count regular files
                    if (attrs.isRegularFile) {
                        fileCount.incrementAndGet()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // Handle permission issues gracefully (common on Windows)
                    loggerFor<FileReadTools>().warn("Warning: Could not access file: {} ({})", file, exc.message)
                    return FileVisitResult.CONTINUE
                }
            })

            fileCount.get()
        } catch (e: Exception) {
            loggerFor<FileReadTools>().error("Failed to count files", e)
            0
        }
    }

    @Tool(description = "Find files using glob patterns. Return absolute paths")
    fun findFiles(glob: String): List<String> = findFiles(glob, findHighest = false)

    /**
     * Find files using glob patterns.
     * @param glob the glob pattern to match files against
     * @param findHighest if true, only the highest matching file in the directory tree will be returned
     * For example, if you want to find all Maven projects by looking for pom.xml files.
     */
    fun findFiles(
        glob: String,
        findHighest: Boolean,
    ): List<String> {
        val basePath = Paths.get(root).toAbsolutePath().normalize()
        val syntaxAndPattern = if (glob.startsWith("glob:") || glob.startsWith("regex:")) glob else "glob:$glob"
        val matcher = FileSystems.getDefault().getPathMatcher(syntaxAndPattern)
        val results = mutableListOf<String>()

        if (!findHighest) {
            return Files.walk(basePath).use { paths ->
                paths.filter { matcher.matches(basePath.relativize(it)) }
                    .map { it.toAbsolutePath().toString() }
                    .toList()
            }
        }

        // We need to process directories breadth-first to find the highest matches
        val processedDirs = mutableSetOf<String>()
        val queue = ArrayDeque<Path>()
        queue.offer(basePath)

        while (queue.isNotEmpty()) {
            val dir = queue.poll()
            val dirStr = dir.toAbsolutePath().toString()

            // Skip if we've already processed this directory
            if (dirStr in processedDirs) {
                continue
            }
            processedDirs.add(dirStr)

            // First, check if this directory itself matches
            if (Files.isRegularFile(dir) && matcher.matches(basePath.relativize(dir))) {
                results.add(dirStr)
                continue
            }

            try {
                // Look for matches in this directory
                val matchesInDir = mutableListOf<String>()
                val subdirs = mutableListOf<Path>()

                Files.newDirectoryStream(dir).use { stream ->
                    stream.forEach { entry ->
                        if (Files.isDirectory(entry)) {
                            subdirs.add(entry)
                        } else if (matcher.matches(basePath.relativize(entry))) {
                            matchesInDir.add(entry.toAbsolutePath().toString())
                        }
                    }
                }

                if (matchesInDir.isNotEmpty()) {
                    // Found matches in this directory, add them and don't process subdirectories
                    results.addAll(matchesInDir)

                    // Mark all subdirectories as processed so we don't look into them
                    subdirs.forEach { subdir ->
                        processedDirs.add(subdir.toAbsolutePath().toString())
                    }
                } else {
                    // No matches in this directory, so process subdirectories
                    queue.addAll(subdirs)
                }
            } catch (_: IOException) {
                loggerFor<FileReadTools>().warn("Failed to read directory at {}", dirStr)
                continue
            }
        }

        return results
    }

    /**
     * Use for safe reading of files. Returns null if the file doesn't exist or is not readable.
     */
    fun safeReadFile(path: String): String? = try {
        readFile(path)
    } catch (e: Exception) {
        loggerFor<FileReadTools>().warn("Failed to read file at {}: {}", path, e.message)
        null
    }

    @Tool(description = "Read a file at the relative path")
    fun readFile(path: String): String {
        val resolvedPath = resolveAndValidateFile(path)
        val rawContent = Files.readString(resolvedPath)
        val transformedContent =
            StringTransformer.transform(rawContent, fileContentTransformers)

        loggerFor<FileReadTools>().debug(
            "Transformed {} content with {} sanitizers: Length went from {} to {}",
            path,
            fileContentTransformers.size,
            "%,d".format(rawContent.length),
            "%,d".format(transformedContent.length),
        )
        recordRead(path)
        return transformedContent
    }

    @Tool(description = "List files and directories at a given path. Prefix is f: for file or d: for directory")
    fun listFiles(path: String): List<String> {
        val resolvedPath = resolvePath(path)
        if (!Files.exists(resolvedPath)) {
            throw IllegalArgumentException("Directory does not exist: $path, root=$root")
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw IllegalArgumentException("Path is not a directory: $path, root=$root")
        }

        return Files.list(resolvedPath).use { stream ->
            stream.map {
                val prefix = if (Files.isDirectory(it)) "d:" else "f:"
                prefix + it.fileName.toString()
            }.sorted().toList()
        }
    }

    fun resolvePath(path: String): Path {
        return resolvePath(root, path)
    }

    fun resolveAndValidateFile(path: String): Path {
        return resolveAndValidateFile(root, path)
    }

}

/**
 * All file modifications must go through this interface.
 */
interface FileWriteTools : DirectoryBased, FileAccessLog, FileChangeLog, SelfToolCallbackPublisher {

    override fun getPathsAccessed(): List<String> = getChanges().map { it.path }.distinct()

    /**
     * Create a file at the relative path under the root
     */
    @Tool(description = "Create a file with the given content")
    fun createFile(
        path: String,
        content: String,
    ): String {
        createFile(path, content, overwrite = false)
        recordChange(FileModification(path, FileModificationType.CREATE))
        return "file created"
    }

    /**
     * Create a file with the given content.
     * @param path the relative path to create the file at
     * @param content the content to write to the file
     * @param overwrite if true, overwrite the file if it already exists
     * @return the path to the created file
     */
    fun createFile(
        path: String,
        content: String,
        overwrite: Boolean,
    ): Path {
        val resolvedPath = resolvePath(root, path)
        if (Files.exists(resolvedPath) && !overwrite) {
            logger.warn("File already exists at {}", path)
            throw IllegalArgumentException("File already exists: $path")
        }

        // Ensure parent directories exist
        Files.createDirectories(resolvedPath.parent)
        return Files.writeString(resolvedPath, content)
    }

    @Tool(description = "Edit the file at the given location. Replace oldContent with newContent. oldContent is typically just a part of the file. e.g. use it to replace a particular method to add another method")
    fun editFile(
        path: String,
        @ToolParam(description = "content to replace") oldContent: String,
        @ToolParam(description = "replacement content") newContent: String,
    ): String {
        logger.info("Editing file at path {}", path)
        logger.debug("File edit at path {}: {} -> {}", path, oldContent, newContent)
        val resolvedPath = resolveAndValidateFile(root = root, path = path)

        val oldFileContent = Files.readString(resolvedPath)
        val newFileContent = oldFileContent.replace(oldContent, newContent)

        return if (newFileContent == oldFileContent) {
            logger.warn(
                "editFile on {} produced no changes: oldContent=[{}], newContent=[{}]",
                resolvedPath,
                oldContent,
                newContent,
            )
            "no changes made"
        } else {
            Files.writeString(resolvedPath, newFileContent)
            logger.info("Edited file at {}", path)
            recordChange(FileModification(path, FileModificationType.EDIT))
            return "file edited"
        }
    }

    // April 25 2005: This method is the first method added to
    // an Embabel project by an Embabel agent
    @Tool(description = "Create a directory at the given path")
    fun createDirectory(path: String): String {
        val resolvedPath = resolvePath(root = root, path = path)
        if (Files.exists(resolvedPath)) {
            if (Files.isDirectory(resolvedPath)) {
                return "directory already exists"
            }
            throw IllegalArgumentException("A file already exists at this path: $path")
        }

        Files.createDirectories(resolvedPath)
        logger.info("Created directory at path: $path")
        recordChange(FileModification(path, FileModificationType.CREATE_DIRECTORY))
        return "directory created"
    }

    @Tool(description = "Append content to an existing file. The file must already exist.")
    fun appendFile(
        path: String,
        content: String,
    ): String {
        val resolvedPath = resolveAndValidateFile(root = root, path = path)
        Files.write(resolvedPath, content.toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        logger.info("Appended content to file at path: $path")
        recordChange(FileModification(path, FileModificationType.APPEND))
        return "content appended to file"
    }

    /**
     * Append content to a file, creating it if it doesn't exist.
     * If create is true, the file will be created if it doesn't exist.
     * If createIfNotExists is false, an exception will be thrown if the file doesn't exist.
     */
    fun appendToFile(
        path: String,
        content: String,
        createIfNotExists: Boolean,
    ) {
        if (createIfNotExists) {
            try {
                createFile(path, content, overwrite = false)
                return
            } catch (_: IllegalArgumentException) {
                // Ignore if the file already exists
            }
        }
        appendFile(path, content)
    }

    @Tool(description = "Delete a file at the given path")
    fun delete(path: String): String {
        val resolvedPath = resolveAndValidateFile(root = root, path = path)
        Files.delete(resolvedPath)
        logger.info("Deleted file at path: $path")
        recordChange(FileModification(path, FileModificationType.DELETE))
        return "file deleted"
    }


    companion object {

        private val logger = LoggerFactory.getLogger(FileTools::class.java)

        /**
         * Create a temporary directory using the given seed
         */
        fun createTempDir(seed: String): File {
            val tempDir = Files.createTempDirectory(seed).toFile()
            val tempDirPath = tempDir.absolutePath
            logger.info("Created temporary directory at {}", tempDirPath)
            return tempDir
        }

        /**
         * Extract zip file to a temporary directory
         * @param zipFile the zip file to extract
         * @param tempDir directory to extract it under
         * @param delete if true, delete the zip file after extraction
         * @return the path to the extracted file content
         */
        fun extractZipFile(
            zipFile: File,
            tempDir: File,
            delete: Boolean,
        ): File {
            val projectDir = tempDir
            ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
                var zipEntry = zipInputStream.nextEntry
                while (zipEntry != null) {
                    val newFile = File(projectDir, zipEntry.name)

                    // Create directories if needed
                    if (zipEntry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        // Create parent directories if needed
                        newFile.parentFile.mkdirs()

                        // Extract file
                        FileOutputStream(newFile).use { fileOutputStream ->
                            zipInputStream.copyTo(fileOutputStream)
                        }
                    }

                    zipInputStream.closeEntry()
                    zipEntry = zipInputStream.nextEntry
                }
            }

            logger.info("Extracted zip file project to {}", projectDir.absolutePath)

            if (delete) {
                zipFile.delete()
            }
            return File(projectDir, zipFile.nameWithoutExtension)
        }
    }

}

/**
 * Resolves a relative path against the root directory
 * Prevents path traversal attacks by ensuring the resolved path is within the root
 */
private fun resolvePath(
    root: String,
    path: String,
): Path {
    val basePath = Paths.get(root).toAbsolutePath().normalize()
    val resolvedPath = basePath.resolve(path).normalize().toAbsolutePath()

    if (!resolvedPath.startsWith(basePath)) {
        throw SecurityException("Path traversal attempt detected: $path, root=$root, resolved='$resolvedPath', base=$'basePath'")
    }
    return resolvedPath
}

/**
 * Resolves a path and validates that it exists and is a regular file
 * @throws IllegalArgumentException if the file doesn't exist or isn't a regular file
 */
private fun resolveAndValidateFile(
    root: String,
    path: String,
): Path {
    val resolvedPath = resolvePath(root = root, path = path)
    if (!Files.exists(resolvedPath)) {
        throw IllegalArgumentException("File does not exist: $path, root=$root")
    }
    if (!Files.isRegularFile(resolvedPath)) {
        throw IllegalArgumentException("Path is not a regular file: $path, root=$root")
    }
    return resolvedPath
}
