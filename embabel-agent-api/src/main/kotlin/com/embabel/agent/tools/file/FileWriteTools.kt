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
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream

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
        Files.write(resolvedPath, content.toByteArray(), StandardOpenOption.APPEND)
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

                    // Ensure zip entry name can be used as a file path
                    val newFile = File(projectDir, zipEntry.name)
                    val canonicalPath = newFile.canonicalPath
                    val prefix = if (projectDir.canonicalPath.endsWith(File.separator)) projectDir.canonicalPath else projectDir.canonicalPath + File.separator
                    if (!canonicalPath.startsWith(prefix, false)) {
                        throw IOException("Invalid zip entry name: ${zipEntry.name}")
                    }

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
